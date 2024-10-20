package com.example.lecturer

import android.annotation.SuppressLint
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lecturer.chatlist.ChatListAdapter
import com.example.lecturer.encryption.EncryptionDecryption
import com.example.lecturer.models.ContentModel
import com.example.lecturer.models.Student
import com.example.lecturer.network.NetworkMessageInterface
import com.example.lecturer.network.Server
import com.example.lecturer.peerlist.PeerListAdapter
import com.example.lecturer.peerlist.PeerListAdapterInterface
import com.example.lecturer.peerlist.StudentAdapter
import com.example.lecturer.peerlist.StudentAdapterInterface
import com.example.lecturer.wifidirect.WifiDirectInterface
import com.example.lecturer.wifidirect.WifiDirectManager

class CommunicationActivity : AppCompatActivity(), WifiDirectInterface, PeerListAdapterInterface, NetworkMessageInterface, StudentAdapterInterface {

    private var wfdManager: WifiDirectManager? = null
    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }
    private var peerListAdapter: PeerListAdapter? = null
    private var chatListAdapter: ChatListAdapter? = null
    private var wfdAdapterEnabled = false
    private var wfdHasConnection = false
    private var hasDevices = false
    private var server: Server? = null
    private var deviceIp: String = ""
    private var selectedPeer: WifiP2pDevice? = null
    private val peerMessagesMap: HashMap<String, MutableList<ContentModel>> = HashMap()
    private val serverMessagesMap: HashMap<String, MutableList<ContentModel>> = HashMap()
    private val studentMap: HashMap<String, Student> = HashMap() // Maps student ID to Student
    private var selectedStudent: String? = null
    private var studentListAdapter: StudentAdapter? = null


    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_communication)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val manager: WifiP2pManager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        val channel = manager.initialize(this, mainLooper, null)
        wfdManager = WifiDirectManager(manager, channel, this)

        chatListAdapter = ChatListAdapter()
        val rvChatList: RecyclerView = findViewById(R.id.rvChat)
        rvChatList.adapter = chatListAdapter
        rvChatList.layoutManager = LinearLayoutManager(this)

        peerListAdapter = PeerListAdapter(this)
        val rvPeerList: RecyclerView = findViewById(R.id.rvPeerListing)
        rvPeerList.adapter = peerListAdapter
        rvPeerList.layoutManager = LinearLayoutManager(this)

        studentListAdapter = StudentAdapter(this)
        val rvStudentList: RecyclerView = findViewById(R.id.rvStudentListing)
        rvStudentList.adapter = studentListAdapter
        rvStudentList.layoutManager = LinearLayoutManager(this)

        wfdManager?.disconnect()
    }
    override fun onResume() {
        super.onResume()
        wfdManager?.also {
            registerReceiver(it, intentFilter)
        }
    }

    override fun onPause() {
        super.onPause()
        wfdManager?.also {
            unregisterReceiver(it)
        }
    }

    fun createGroup(view: View) {
        wfdManager?.createGroup()
        updateUI()
    }

    fun endClass(view: View) {
        wfdManager?.disconnect()
        updateUI()
    }

    private fun updateUI() {
        val wfdAdapterErrorView: ConstraintLayout = findViewById(R.id.clWfdAdapterDisabled)
        wfdAdapterErrorView.visibility = if (!wfdAdapterEnabled) View.VISIBLE else View.GONE

        val wfdNoConnectionView: ConstraintLayout = findViewById(R.id.clNoWifiDirectConnection)
        wfdNoConnectionView.visibility = if (wfdAdapterEnabled && !wfdHasConnection) View.VISIBLE else View.GONE

        val rvPeerList: RecyclerView = findViewById(R.id.rvStudentListing)
        rvPeerList.visibility = if (wfdAdapterEnabled && hasDevices) View.VISIBLE else View.GONE

        val wfdConnectedView: ConstraintLayout = findViewById(R.id.clHasConnection)
        wfdConnectedView.visibility = if (wfdHasConnection) View.VISIBLE else View.GONE

        // Optionally display SSID and password if connected
        if (wfdHasConnection) {
            val ssidTextView: TextView = findViewById(R.id.tvNetworkSSID)
            val passwordTextView: TextView = findViewById(R.id.tvNetworkPassword)

            val ssid = "WiFi Direct SSID: ${wfdManager?.groupInfo?.networkName}"
            val password = "Password: ${wfdManager?.groupInfo?.passphrase}"

            ssidTextView.text = ssid
            passwordTextView.text = password
        }
    }

    fun sendMessage(view: View) {
        val etMessage: EditText = findViewById(R.id.etMessage)
        val etString = etMessage.text.toString()

        if (selectedStudent != null) {
            val serverContent = ContentModel(etString, deviceIp, selectedStudent)
            serverMessagesMap.getOrPut(selectedStudent!!) { mutableListOf() }.add(serverContent)
            etMessage.text.clear()
            chatListAdapter?.addItemToEnd(serverContent)

            // Encrypt Message
            val encryptionDecryption = EncryptionDecryption()
            val aesKey = encryptionDecryption.generateAESKey(selectedStudent!!) // Assuming student ID is used as the seed
            val aesIv = encryptionDecryption.generateIV(selectedStudent!!)
            val encryptedMessage = encryptionDecryption.encryptMessage(etString, aesKey, aesIv)

            // Prepare encrypted content
            val encryptedContent = ContentModel(encryptedMessage, deviceIp, selectedStudent)

            // Send encrypted message in a background thread
            sendMessageToClient(encryptedContent)
        } else {
            Log.e("Chat", "No peer selected. Cannot send message")
        }
    }

    private fun sendMessageToClient(content: ContentModel) {
        Thread {
            server?.sendMessageToStudent(selectedStudent!!, content) // Pass the student ID and content
        }.start()
    }



    override fun onWiFiDirectStateChanged(isEnabled: Boolean) {
        wfdAdapterEnabled = isEnabled
        val text = if (isEnabled) {
            "There was a state change in the WiFi Direct. Currently it is enabled!"
        } else {
            "There was a state change in the WiFi Direct. Currently it is disabled! Try turning on the WiFi adapter"
        }
        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        toast.show()
        updateUI()
    }

    override fun onPeerListUpdated(deviceList: Collection<WifiP2pDevice>) {
        val toast = Toast.makeText(this, "Updated listing of nearby WiFi Direct devices", Toast.LENGTH_SHORT)
        toast.show()
        hasDevices = deviceList.isNotEmpty()
        peerListAdapter?.updateList(deviceList)
        updateUI()
    }

    override fun onStudentListUpdated(studentList: Collection<String>) {
        Log.d("CommunicationActivity", "onStudentListUpdated called with: $studentList")
        runOnUiThread {
            hasDevices = studentList.isNotEmpty()
            studentListAdapter?.updateList(studentList)
            updateUI()
        }
    }


    override fun onGroupStatusChanged(groupInfo: WifiP2pGroup?) {
        val text = if (groupInfo == null) {
            "Group is not formed"
        } else {
            "Group has been formed"
        }
        val toast = Toast.makeText(this, text, Toast.LENGTH_SHORT)
        toast.show()
        wfdHasConnection = groupInfo != null

        // for group status changes
        if (groupInfo == null) {
            server?.close()
            server = null
        } else {
            // If the group is formed
            if (groupInfo.isGroupOwner) {
                // start server as GO
                if (server == null) {
                    server = Server(this)
                    deviceIp = "192.168.49.1"
                }
            }
        }
        updateUI()
    }

    override fun onDeviceStatusChanged(thisDevice: WifiP2pDevice) {
        val toast = Toast.makeText(this, "Device parameters have been updated", Toast.LENGTH_SHORT)
        toast.show()
    }

    override fun onStudentClicked(studentId: String) {
        selectedStudent = studentId
        updateStudentChatUI(studentId)
    }

    private fun updateStudentChatUI(studentId: String) {
        // Update the UI to show the chat interface for the selected student
        val chatInterface: ConstraintLayout = findViewById(R.id.clChatInterface)
        chatInterface.visibility = View.VISIBLE

        val studentChatTitle: TextView = findViewById(R.id.tvStudentID)
        studentChatTitle.text = "Student Chat - $studentId"


    }

    override fun onContent(content: ContentModel) {
        runOnUiThread {
            chatListAdapter?.addItemToEnd(content)
        }
    }


    override fun onPeerClicked(peer: WifiP2pDevice) {
        // Handle peer click
    }


}