package com.example.lecturer.network

import android.util.Log
import com.example.lecturer.encryption.EncryptionDecryption
import com.example.lecturer.models.ContentModel
import com.google.gson.Gson
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/// The [Server] class has all the functionality that is responsible for the 'server' connection.
/// This is implemented using TCP. This Server class is intended to be run on the GO.

class Server(private val iFaceImpl: NetworkMessageInterface) {
    companion object {
        const val PORT: Int = 9999
    }

    private val svrSocket: ServerSocket = ServerSocket(PORT, 0, InetAddress.getByName("192.168.49.1"))
    val clientMap: HashMap<String, Socket> = HashMap()
    private lateinit var aesKey: SecretKeySpec
    private lateinit var aesIV: IvParameterSpec
    private val encryptionDecryption = EncryptionDecryption()

    init {
        // Example hashed ID; in a real scenario, you'd want this to be unique for each client.
        val hashedID = encryptionDecryption.hashStrSha256("816117992")
        aesKey = encryptionDecryption.generateAESKey(hashedID)
        aesIV = encryptionDecryption.generateIV(hashedID)

        thread {
            while (true) {
                try {
                    val clientConnectionSocket = svrSocket.accept()
                    Log.e("SERVER", "The server has accepted a connection: ${clientConnectionSocket.inetAddress.hostAddress}")
                    handleSocket(clientConnectionSocket)
                } catch (e: Exception) {
                    Log.e("SERVER", "An error has occurred in the server!")
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        socket.inetAddress.hostAddress?.let { clientIp ->
            clientMap[clientIp] = socket
            Log.e("SERVER", "A new connection has been detected from: $clientIp")
            thread {
                val clientReader = socket.inputStream.bufferedReader()
                var receivedJson: String?

                // Read incoming messages from client
                while (socket.isConnected) {
                    try {
                        receivedJson = clientReader.readLine()
                        if (receivedJson != null) {
                            Log.e("SERVER", "Received a message from client $clientIp")
                            val decryptedMessage = encryptionDecryption.decryptMessage(receivedJson, aesKey, aesIV)
                            val clientContent = Gson().fromJson(decryptedMessage, ContentModel::class.java)

                            // Let the networkMessageInterface handle the received content
                            iFaceImpl.onContent(clientContent)

                            // Optionally: respond to the client (but not mandatory in a messaging app)
                        }
                    } catch (e: Exception) {
                        Log.e("SERVER", "An error has occurred with the client $clientIp")
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    // New method to send a message to a specific client
    fun sendMessageToClient(clientIp: String, content: ContentModel) {
        val socket = clientMap[clientIp]
        if (socket != null && !socket.isClosed) {
            thread {
                try {
                    val writer = socket.outputStream.bufferedWriter()
                    val contentAsStr = Gson().toJson(content)
                    val encryptedMessage = encryptionDecryption.encryptMessage(contentAsStr, aesKey, aesIV)
                    writer.write("$encryptedMessage\n")
                    writer.flush()
                } catch (e: Exception) {
                    Log.e("SERVER", "Failed to send message to client $clientIp")
                    e.printStackTrace()
                }
            }
        } else {
            Log.e("SERVER", "No active connection found for client $clientIp")
        }
    }


    // New method: Send a message to all connected clients (broadcast)
    fun broadcastMessage(content: ContentModel) {
        clientMap.forEach { (ip, socket) ->
            thread {
                try {
                    val writer = socket.outputStream.bufferedWriter()
                    val contentAsStr = Gson().toJson(content)
                    val encryptedMessage = encryptionDecryption.encryptMessage(contentAsStr, aesKey, aesIV)
                    writer.write("$encryptedMessage\n")
                    writer.flush()
                } catch (e: Exception) {
                    Log.e("SERVER", "Failed to send message to client $ip")
                    e.printStackTrace()
                }
            }
        }
    }

    fun close() {
        svrSocket.close()
        clientMap.values.forEach { it.close() }
        clientMap.clear()
    }
}
