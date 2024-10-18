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

class CommunicationActivity : AppCompatActivity(), WifiDirectInterface, PeerListAdapterInterface,
    NetworkMessageInterface {

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
    private var currentPeerIp: String? = null

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

        // Display SSID and password if connected
        if (wfdHasConnection) {
            val ssidTextView: TextView = findViewById(R.id.tvNetworkSSID)
            val passwordTextView: TextView = findViewById(R.id.tvNetworkPassword)

            val ssid = "WiFi Direct SSID: ${wfdManager?.groupInfo?.networkName}"
            val password = "Password: ${wfdManager?.groupInfo?.passphrase}"

            ssidTextView.text = ssid
            passwordTextView.text = password
        }

        // Update chat UI if a peer is selected
        currentPeerIp?.let { peerIp ->
            server?.clientMap?.keys?.find { it == peerIp }?.let { foundIp ->
                val peer = peerListAdapter?.getDeviceByIp(foundIp)
                peer?.let {
                    updateChatUIForSelectedPeer(it)
                }
            }
        }
    }



    fun sendMessage(view: View) {
        val etMessage: EditText = findViewById(R.id.etMessage)
        val etString = etMessage.text.toString().trim()
        if (etString.isEmpty()) return

        val content = ContentModel(etString, deviceIp)
        etMessage.text.clear()

        if (wfdHasConnection && server != null) {
            // If a specific peer is selected, send the message only to that peer
            currentPeerIp?.let { peerIp ->
                server?.sendMessageToClient(peerIp, content)
            } ?: run {
                // Otherwise, broadcast the message to all clients
                server?.broadcastMessage(content)
            }
        }
        // Add the message to the local chat list
        chatListAdapter?.addItemToEnd(content)
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
        hasDevices = deviceList.isNotEmpty()
        peerListAdapter?.updateList(deviceList)
        Log.d("CommunicationActivity", "Number of peers: ${deviceList.size}")
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
    }


    override fun onDeviceStatusChanged(thisDevice: WifiP2pDevice) {
        val toast = Toast.makeText(this, "Device parameters have been updated", Toast.LENGTH_SHORT)
        toast.show()
    }

    override fun onPeerClicked(peer: WifiP2pDevice) {
        Log.d("CommunicationActivity", "Peer clicked: ${peer.deviceName}")
        Toast.makeText(this, "Clicked on: ${peer.deviceName}", Toast.LENGTH_SHORT).show() // Add this line
        val peerIp = peer.deviceAddress

        if (server?.clientMap?.containsKey(peerIp) == true) {
            // Set the current peer IP for communication
            currentPeerIp = peerIp
        }

        updateUI() // This will handle the chat UI update
    }



    @SuppressLint("SetTextI18n")
    private fun updateChatUIForSelectedPeer(peer: WifiP2pDevice) {
        Log.d("ChatUI", "Updating chat UI for: ${peer.deviceName}")

        // Clear the current chat list
        chatListAdapter?.clearChat()

        // Check that peer.deviceName is not null or empty
        if (peer.deviceName.isNullOrEmpty()) {
            Log.e("ChatUI", "Peer device name is empty or null!")
        } else {
            // Update the selected peer's TextView
            val selectedPeerTextView: TextView = findViewById(R.id.tvStudentID)
            selectedPeerTextView.text = "Chatting with: ${peer.deviceName}"
            Log.d("ChatUI", "Updated student ID: ${peer.deviceName}")
        }

        // Ensure chat interface is visible
        val chatLayout: ConstraintLayout = findViewById(R.id.rvChat)
        chatLayout.visibility = View.VISIBLE
    }




    override fun onContent(content: ContentModel) {
        runOnUiThread {
            chatListAdapter?.addItemToEnd(content)
        }
        updateUI()

    }
}
