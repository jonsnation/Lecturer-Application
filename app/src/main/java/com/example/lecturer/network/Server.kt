package com.example.lecturer.network

import android.util.Log
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

    private fun handleSocket(socket: Socket) {
        val reader = socket.inputStream.bufferedReader()
        val writer = socket.outputStream.bufferedWriter()

        thread {
            try {
                while (socket.isConnected) {
                    val message = reader.readLine() ?: break
                    val content = Gson().fromJson(message, ContentModel::class.java)
                    val studentId = content.studentId

                    if (studentId != null) {
                        handleStudentConnection(socket, content, writer, studentId)
                    }
                }
            } catch (e: Exception) {
                Log.e("SERVER", "Error handling socket: ${e.message}")
            } finally {
                cleanUp(socket)
            }
        }
    }

    private fun handleStudentConnection(socket: Socket, content: ContentModel, writer: BufferedWriter, studentId: String) {
        if (!isValidStudent(studentId)) {
            Log.e("SERVER", "Student $studentId is not part of the class")
            removeClient(studentId)
            return
        }

        when {
            isChallengeInitiation(content, studentId) -> {
                Log.d("SERVER", "Initiating challenge for student $studentId")
                initiateChallenge(writer, studentId)
            }
            isChallengeResponse(content, studentId) -> {
                Log.d("SERVER", "Verifying challenge response from student $studentId")
                verifyChallenge(content, studentId, socket)
            }
            isLeavingRequest(content, studentId) -> {
                Log.d("SERVER", "Student $studentId has requested to leave")
                handleLeavingRequest(writer, studentId)
            }
            else -> {
                Log.d("SERVER", "Processing message from student $studentId")
                processStudentMessage(content, studentId)
            }
        }
    }

    private fun isValidStudent(studentId: String): Boolean {
        return classStudentIds.contains(studentId)
    }

    private fun isChallengeInitiation(content: ContentModel, studentId: String): Boolean {
        return content.message == "I am here" && !authorizedList.contains(studentId)
    }

    private fun isChallengeResponse(content: ContentModel, studentId: String): Boolean {
        return challengeList[studentId] != null
    }

    private fun isLeavingRequest(content: ContentModel, studentId: String): Boolean {
        return content.message == "leaving" && authorizedList.contains(studentId)
    }

    private fun initiateChallenge(writer: BufferedWriter, studentId: String) {
        val randomR = generateNonce()
        challengeList[studentId] = randomR
        sendMessage(writer, ContentModel(randomR, "192.168.49.1", studentId))
        Log.d("SERVER", "Started challenge for student $studentId with nonce $randomR")
    }

    private fun verifyChallenge(content: ContentModel, studentId: String, socket: Socket) {
        val randomR = challengeList[studentId]
        val encryptedMessage = content.message
        Log.d("SERVER", "Received encrypted response from student $studentId: $encryptedMessage")
        val decryptedMessage = decryptMessageWithID(encryptedMessage, studentId)

        Log.d("SERVER", "Decrypted message from student $studentId: $decryptedMessage")

        if (randomR == decryptedMessage) {
            authorizeStudent(studentId, socket)
            Log.d("SERVER", "Student $studentId authenticated successfully")
        } else {
            Log.e("SERVER", "Failed to authenticate student $studentId")
            removeClient(studentId)
        }
    }

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

        // Update the student list and notify the interface
        updateStudentList()
    }

    private fun updateStudentList() {
        // Update the student list and notify the UI
        val updatedStudentList = authorizedList.toList() // Convert to immutable list if necessary
        iFaceImpl.onStudentListUpdated(updatedStudentList)
        Log.d("SERVER", "Updated student list: $updatedStudentList")
    }

    private fun handleLeavingRequest(writer: BufferedWriter, studentId: String) {
        sendMessage(writer, ContentModel("leaving", "192.168.49.1", studentId))
        removeClient(studentId)
        Log.d("SERVER", "Student $studentId has left and was removed")
    }

    private fun processStudentMessage(content: ContentModel, studentId: String) {
        clientMap[studentId]?.let {
            iFaceImpl.onContent(content)
            Log.d("SERVER", "Received message from student $studentId: ${content.message}")
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

    private fun removeClient(studentId: String) {
        val socket = clientMap[studentId]
        socket?.close()
        clientMap.remove(studentId)
        challengeList.remove(studentId)
        Log.d("SERVER", "Removed client with student ID $studentId")
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
}