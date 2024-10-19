package com.example.lecturer.network

import android.util.Log
import com.example.lecturer.encryption.EncryptionDecryption
import com.example.lecturer.models.ContentModel
import com.google.gson.Gson
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class Server(private val iFaceImpl: NetworkMessageInterface) {
    companion object {
        const val PORT: Int = 9999
    }

    private val svrSocket: ServerSocket = ServerSocket(PORT, 0, InetAddress.getByName("192.168.49.1"))
    private val clientMap: HashMap<String, Socket> = HashMap()  // Maps IP address to Socket
    private val studentIdMap: MutableMap<String, String?> = mutableMapOf()  // Maps IP address to student ID
    private val encryptionDecryption = EncryptionDecryption()

    // List of student IDs from 816000000 to 816999999
    val classStudentIds = (816000000..816999999).map { it.toString() }

    private lateinit var aesKey: SecretKeySpec
    private lateinit var aesIV: IvParameterSpec

    init {
        thread {
            while (true) {
                val socket = svrSocket.accept()
                handleSocket(socket)
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        socket.inetAddress.hostAddress?.let { clientIp ->  // Use IP address, not MAC address
            clientMap[clientIp] = socket
            val reader = socket.inputStream.bufferedReader()
            val writer = socket.outputStream.bufferedWriter()

            thread {
                try {
                    while (true) {
                        val message = reader.readLine() ?: break
                        val content = Gson().fromJson(message, ContentModel::class.java)

                        if (content.message == "I am here") {
                            // Generate and send nonce
                            val nonce = generateNonce()
                            Log.d("SERVER", "Received 'I am here' message from client $clientIp with student ID: ${content.studentId}")
                            Log.d("SERVER", "Sending nonce (R) to client $clientIp: $nonce")
                            writer.write(Gson().toJson(ContentModel(nonce, "192.168.49.1", content.studentId)) + "\n")
                            writer.flush()

                            // Map the student ID to the client IP
                            studentIdMap[clientIp] = content.studentId
                            Log.d("SERVER", "Mapped student ID ${content.studentId} to client IP $clientIp")
                        } else {
                            // Verify client
                            val studentId = verifyClient(content.message)
                            if (studentId != null) {
                                studentIdMap[clientIp] = studentId  // Map IP to student ID
                                Log.i("SERVER", "Client $clientIp authenticated as $studentId")
                            } else {
                                Log.e("SERVER", "Failed to authenticate client $clientIp")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SERVER", "Error handling socket for client $clientIp")
                    e.printStackTrace()
                } finally {
                    clientMap.remove(clientIp)
                    studentIdMap.remove(clientIp)
                    socket.close()
                }
            }
        }
    }

    private fun verifyClient(encryptedMessage: String): String? {
        for (studentId in classStudentIds) {
            try {
                val hashedID = encryptionDecryption.hashStrSha256(studentId)
                aesKey = encryptionDecryption.generateAESKey(hashedID)
                aesIV = encryptionDecryption.generateIV(hashedID)
                val decryptedNonce = encryptionDecryption.decryptMessage(encryptedMessage, aesKey, aesIV)
                if (decryptedNonce.length == 32) {
                    return studentId
                }
            } catch (e: Exception) {
                // Ignore and try next student ID
            }
        }
        return null
    }

    private fun generateNonce(): String {
        val random = SecureRandom()
        val nonce = ByteArray(16)
        random.nextBytes(nonce)
        return nonce.joinToString("") { "%02x".format(it) }
    }

    // Send message to a student using their student ID
    fun sendMessageToStudent(studentId: String, content: ContentModel) {
        // Find the client's IP address by looking up the student ID in studentIdMap
        val clientIp = studentIdMap.entries.find { it.value == studentId }?.key

        if (clientIp != null) {
            val socket = clientMap[clientIp]  // Use the IP to get the socket
            if (socket != null && !socket.isClosed) {
                thread {
                    try {
                        val writer = socket.outputStream.bufferedWriter()
                        val contentAsStr: String = Gson().toJson(content)
                        val encryptedMessage = encryptionDecryption.encryptMessage(contentAsStr, aesKey, aesIV)
                        writer.write("$encryptedMessage\n")
                        writer.flush()
                    } catch (e: Exception) {
                        Log.e("SERVER", "Error sending message to student $studentId (client IP: $clientIp)")
                        e.printStackTrace()
                    }
                }
            } else {
                Log.e("SERVER", "No active connection found for student $studentId (client IP: $clientIp)")
            }
        } else {
            Log.e("SERVER", "Student $studentId not found in the connected clients list")
        }
    }

    // Get student ID using client IP address
    fun getStudentIdByClientIp(clientIp: String): String? {
        val studentId = studentIdMap[clientIp]
        Log.d("Server", "getStudentIdByClientIp: clientIp=$clientIp, studentId=$studentId")
        return studentId
    }

    // Get client IP using MAC address
    fun getClientIp(macAddress: String): String? {
        return clientMap.entries.find { it.value.inetAddress.hostAddress == macAddress }?.key
    }

    fun close() {
        svrSocket.close()
        clientMap.values.forEach { it.close() }
        clientMap.clear()
    }
}