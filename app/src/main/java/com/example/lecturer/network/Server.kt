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

class Server(private val iFaceImpl: NetworkMessageInterface) {
    companion object {
        const val PORT: Int = 9999
    }

    private val svrSocket: ServerSocket = ServerSocket(PORT, 0, InetAddress.getByName("192.168.49.1"))
    private val clientMap: HashMap<String, Socket> = HashMap()
    private val encryptionDecryption = EncryptionDecryption()
    private val classStudentIds = generateStudentIds()
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

    private fun generateStudentIds(): List<String> {
        val studentIds = mutableSetOf("816117992")
        val random = SecureRandom()
        while (studentIds.size < 49) {
            val id = "816" + (random.nextInt(1000000)).toString().padStart(6, '0')
            studentIds.add(id)
        }
        return studentIds.toList()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleSocket(socket: Socket) {
        val reader = socket.inputStream.bufferedReader()
        val writer = socket.outputStream.bufferedWriter()

        thread {
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

        for (studentId in classStudentIds) {
            val decryptedMessage = decryptMessageWithID(encryptedMessage, studentId)
            if (randomR == decryptedMessage) {
                authorizeStudent(studentId, socket)
                return
            }
        }

        Log.e("SERVER", "Failed to authenticate student")
        socket.close()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun decryptMessageWithID(encryptedMessage: String, studentId: String): String {
        val hashedID = encryptionDecryption.hashStrSha256(studentId)
        val aesKey = encryptionDecryption.generateAESKey(hashedID)
        val aesIV = encryptionDecryption.generateIV(hashedID)
        return encryptionDecryption.decryptMessage(encryptedMessage, aesKey, aesIV)
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
        val contentStr = Gson().toJson(content)
        writer.write("$contentStr\n")
        writer.flush()
        Log.d("SERVER", "Sent message: $contentStr")
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
    }
}