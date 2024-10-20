package com.example.lecturer.network

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.lecturer.encryption.EncryptionDecryption
import com.example.lecturer.models.ContentModel
import com.google.gson.Gson
import java.io.BufferedWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

@RequiresApi(Build.VERSION_CODES.O)
class Server(private val iFaceImpl: NetworkMessageInterface) {
    companion object {
        const val PORT: Int = 9999
    }

    private val svrSocket: ServerSocket = ServerSocket(PORT, 0, InetAddress.getByName("192.168.49.1"))
    private val clientMap: HashMap<String, Socket> = HashMap()
    private val encryptionDecryption = EncryptionDecryption()
    private val classStudentIds = getHardCodedStudentIds()
    private val authorizedList: MutableList<String> = mutableListOf()
    private val challengeList: MutableMap<String, String> = mutableMapOf()

    init {
        thread {
            try {
                while (!svrSocket.isClosed) {
                    val socket = svrSocket.accept()
                    handleSocket(socket)
                }
            } catch (e: Exception) {
                Log.e("SERVER", "Error accepting connection: ${e.message}")
            }
        }
    }

    private fun getHardCodedStudentIds(): List<String> {
        return listOf(
            "816117992", "816117993", "816117994", "816117995", "816117996",
            "816117997", "816117998", "816117999", "816118000", "816118001",
            "816118002", "816118003", "816118004", "816118005", "816118006",
            "816118007", "816118008", "816118009", "816118010", "816118011",
            "816118012", "816118013", "816118014", "816118015", "816118016",
            "816118017", "816118018", "816118019", "816118020", "816118021",
            "816118022", "816118023", "816118024", "816118025", "816118026",
            "816118027", "816118028", "816118029", "816118030", "816118031",
            "816118032", "816118033", "816118034", "816118035", "816118036",
            "816118037", "816118038", "816118039", "816118040", "816035483"
        )
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleSocket(socket: Socket) {
        thread {
            val reader = socket.inputStream.bufferedReader()
            val writer = socket.outputStream.bufferedWriter()

            try {
                while (socket.isConnected) {
                    val message = reader.readLine() ?: break
                    val content = Gson().fromJson(message, ContentModel::class.java)

                    if (content.message == "I am here") {
                        initiateChallenge(writer)
                    } else if (content.studentId == null) {
                        verifyChallenge(content, socket)
                    } else {
                        processStudentMessage(content)
                    }
                }
            } catch (e: Exception) {
                Log.e("SERVER", "Error handling socket: ${e.message}")
            } finally {
                cleanUp(socket)
            }
        }
    }

    private fun initiateChallenge(writer: BufferedWriter) {
        val randomR = generateNonce()
        challengeList["pending"] = randomR
        sendMessage(writer, ContentModel(randomR, "192.168.49.1", null))
        Log.d("SERVER", "Started challenge with nonce $randomR")
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun verifyChallenge(content: ContentModel, socket: Socket) {
        val randomR = challengeList["pending"]
        val encryptedMessage = content.message

        Log.d("SERVER", "Verifying challenge for message: $encryptedMessage with nonce: $randomR")

        if (randomR == null || encryptedMessage == null) {
            Log.e("SERVER", "Challenge or encrypted message is null")
            socket.close()
            return
        }

        for (studentId in classStudentIds) {
            val decryptedMessage = decryptMessageWithID(encryptedMessage, studentId)
            Log.d("SERVER", "Decrypted message with student ID $studentId: $decryptedMessage")
            if (randomR == decryptedMessage) {
                authorizeStudent(studentId, socket)
                return
            }
        }

        Log.e("SERVER", "Failed to authenticate student")
        socket.close()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun decryptMessageWithID(encryptedMessage: String, studentId: String): String? {
        return try {
            val hashedID = encryptionDecryption.hashStrSha256(studentId)
            val aesKey = encryptionDecryption.generateAESKey(hashedID)
            val aesIV = encryptionDecryption.generateIV(hashedID)
            Log.d("SERVER", "Decrypting with key: ${aesKey.encoded.joinToString("") { "%02x".format(it) }} and IV: ${aesIV.iv.joinToString("") { "%02x".format(it) }}")
            val decryptedMessage = encryptionDecryption.decryptMessage(encryptedMessage, aesKey, aesIV)
            Log.d("SERVER", "Decrypted message: $decryptedMessage")
            decryptedMessage
        } catch (e: Exception) {
            Log.e("SERVER", "Error decrypting message: ${e.message}")
            null
        }
    }

    private fun authorizeStudent(studentId: String, socket: Socket) {
        authorizedList.add(studentId)
        clientMap[studentId] = socket
        Log.d("SERVER", "Authorized student $studentId and added to client map")
        updateStudentList()
    }

    private fun updateStudentList() {
        val updatedStudentList = authorizedList.toList()
        iFaceImpl.onStudentListUpdated(updatedStudentList)
        Log.d("SERVER", "Updated student list: $updatedStudentList")
    }

    private fun processStudentMessage(content: ContentModel) {
        val recipientId = content.studentId
        if (recipientId != null) {
            sendMessageToStudent(recipientId, content)
        }
    }

    private fun sendMessage(writer: BufferedWriter, content: ContentModel) {
        thread {
            try {
                val contentStr = Gson().toJson(content)
                writer.write("$contentStr\n")
                writer.flush()
                Log.d("SERVER", "Sent message: $contentStr")
            } catch (e: Exception) {
                Log.e("SERVER", "Error sending message: ${e.message}")
            }
        }
    }

    private fun cleanUp(socket: Socket) {
        val studentId = getStudentIdFromSocket(socket)
        if (studentId != null) {
            clientMap.remove(studentId)
            Log.d("SERVER", "Cleaned up and removed student $studentId from client map")
        }
    }

    private fun generateNonce(): String {
        val random = SecureRandom()
        val nonce = ByteArray(16)
        random.nextBytes(nonce)
        return nonce.joinToString("") { "%02x".format(it) }
    }

    private fun getStudentIdFromSocket(socket: Socket): String? {
        return clientMap.entries.find { it.value == socket }?.key
    }

    fun close() {
        svrSocket.close()
        clientMap.clear()
        challengeList.clear()
        authorizedList.clear()
        Log.d("SERVER", "Server closed and all data cleared")
    }

    fun sendMessageToStudent(studentId: String, content: ContentModel) {
        thread {
            try {
                val socket = clientMap[studentId]
                if (socket != null) {
                    val writer = socket.outputStream.bufferedWriter()
                    val contentAsStr = Gson().toJson(content)
                    writer.write("$contentAsStr\n")
                    writer.flush()
                    Log.d("SERVER", "Sent message to student $studentId: $contentAsStr")
                } else {
                    Log.e("SERVER", "No active connection found for student $studentId")
                }
            } catch (e: Exception) {
                Log.e("SERVER", "Error sending message to student $studentId: ${e.message}")
            }
        }
    }
}