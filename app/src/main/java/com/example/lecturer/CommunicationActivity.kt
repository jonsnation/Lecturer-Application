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
import com.example.lecturer.models.ContentModel
import com.example.lecturer.network.NetworkMessageInterface
import com.example.lecturer.network.Server
import com.example.lecturer.peerlist.PeerListAdapter
import com.example.lecturer.peerlist.PeerListAdapterInterface
import com.example.lecturer.wifidirect.WifiDirectInterface
import com.example.lecturer.wifidirect.WifiDirectManager

class CommunicationActivity : AppCompatActivity(), WifiDirectInterface, PeerListAdapterInterface, NetworkMessageInterface {

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

        peerListAdapter = PeerListAdapter(this)
        val rvPeerList: RecyclerView = findViewById(R.id.rvPeerListing)
        rvPeerList.adapter = peerListAdapter
        rvPeerList.layoutManager = LinearLayoutManager(this)

        chatListAdapter = ChatListAdapter()
        val rvChatList: RecyclerView = findViewById(R.id.rvChat)
        rvChatList.adapter = chatListAdapter
        rvChatList.layoutManager = LinearLayoutManager(this)

        // Remove existing group if any
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

    fun discoverNearbyPeers(view: View) {
        wfdManager?.discoverPeers()
    }

    private fun updateUI() {
        val wfdAdapterErrorView: ConstraintLayout = findViewById(R.id.clWfdAdapterDisabled)
        wfdAdapterErrorView.visibility = if (!wfdAdapterEnabled) View.VISIBLE else View.GONE

        val wfdNoConnectionView: ConstraintLayout = findViewById(R.id.clNoWifiDirectConnection)
        wfdNoConnectionView.visibility = if (wfdAdapterEnabled && !wfdHasConnection) View.VISIBLE else View.GONE

        val rvPeerList: RecyclerView = findViewById(R.id.rvPeerListing)
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
        val messageText = etMessage.text.toString().trim()

        if (messageText.isEmpty()) return

        // Get the selected peer's IP address and map it to a student ID
        val macAddress = selectedPeer?.deviceAddress
        val clientIp = macAddress?.let { server?.getClientIp(it) }
        val studentId = clientIp?.let { server?.getStudentIdByClientIp(it) }

        // Log the clientIp and studentId for debugging
        Log.d("CommunicationActivity", "sendMessage: macAddress=$macAddress, clientIp=$clientIp, studentId=$studentId")

        if (clientIp != null && studentId != null) {
            // Create a ContentModel object, include the studentId and message
            val content = ContentModel(messageText, deviceIp, studentId)

            etMessage.text.clear()

            if (wfdHasConnection && server != null) {
                // Send the message to the server, which will handle delivery to the client
                server?.sendMessageToStudent(studentId, content)

                // Add the message to the local chat list (displayed on the lecturer's device)
                chatListAdapter?.addItemToEnd(content)

                // Also save it in the peer's message history map
                peerMessagesMap.getOrPut(studentId) { mutableListOf() }.add(content)
            }
        } else {
            Toast.makeText(this, "No peer selected or student not authenticated", Toast.LENGTH_SHORT).show()
        }
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
        Log.d("CommunicationActivity", "Number of peers: ${deviceList.size}")

        hasDevices = deviceList.isNotEmpty()  // Check if there are devices available

        // Map devices to student IDs
        val peersWithIds = deviceList.map { device ->
            val clientIp = server?.getClientIp(device.deviceAddress)
            val studentId = clientIp?.let { server?.getStudentIdByClientIp(it) } ?: "Unknown"
            device to studentId
        }

        peerListAdapter?.updateList(peersWithIds)  // Update the adapter with the new peer list
        updateUI()
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

    @SuppressLint("SetTextI18n")
    override fun onPeerClicked(peer: WifiP2pDevice) {
        selectedPeer = peer
        val clientIp = peerListAdapter?.getPeerByIp(peer.deviceAddress)?.deviceAddress
        val studentId = server?.getStudentIdByClientIp(clientIp ?: "")
        findViewById<TextView>(R.id.tvStudentID).text = "Chatting with: ${studentId ?: "Unknown"}"
        updateStudentChatUI(peer)
    }

    private fun updateStudentChatUI(peer: WifiP2pDevice) {
        val studentId = server?.getStudentIdByClientIp(peer.deviceAddress)
        val studentMessages = peerMessagesMap[studentId] ?: mutableListOf()
        val serverMessages = serverMessagesMap[studentId] ?: mutableListOf()

        val messages = (studentMessages + serverMessages).sortedBy { it.timestamp }
        chatListAdapter?.updateChat(messages)

        val clChatInterface: ConstraintLayout = findViewById(R.id.clChatInterface)
        clChatInterface.visibility = if (selectedPeer != null) View.VISIBLE else View.GONE
    }

    override fun onContent(content: ContentModel) {
        runOnUiThread {
            val receivedMessage = content.message
            val clientIp = content.senderIp
            val studentId = server?.getStudentIdByClientIp(clientIp)

            chatListAdapter?.addItemToEnd(content)
            if (studentId != null) {
                peerMessagesMap.getOrPut(studentId) { mutableListOf() }.add(content)
            }
            Toast.makeText(this, "ID: $studentId\nIP: $clientIp", Toast.LENGTH_SHORT).show()
        }
        updateUI()
    }
}