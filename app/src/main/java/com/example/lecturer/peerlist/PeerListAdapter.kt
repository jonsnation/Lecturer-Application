package com.example.lecturer.peerlist

import android.annotation.SuppressLint
import android.net.wifi.p2p.WifiP2pDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lecturer.R

class PeerListAdapter(private val iFaceImpl: PeerListAdapterInterface): RecyclerView.Adapter<PeerListAdapter.ViewHolder>() {
    private val peersList: MutableList<Pair<WifiP2pDevice, String?>> = mutableListOf()

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val titleTextView: TextView = itemView.findViewById(R.id.titleTextView)
        val descriptionTextView: TextView = itemView.findViewById(R.id.descriptionTextView)
        val askQuestionButton: TextView = itemView.findViewById(R.id.btnAskQuestion)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.peer_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (peer, studentId) = peersList[position]

        holder.titleTextView.text = studentId ?: peer.deviceName
        holder.descriptionTextView.text = peer.deviceAddress
        holder.askQuestionButton.setOnClickListener {
            iFaceImpl.onPeerClicked(peer)
        }
    }

    override fun getItemCount(): Int {
        return peersList.size
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateList(newPeersList: Collection<Pair<WifiP2pDevice, String?>>) {
        peersList.clear()
        peersList.addAll(newPeersList)
        notifyDataSetChanged()
    }

    fun getPeerByIp(macAddress: String): WifiP2pDevice? {
        return peersList.find { it.first.deviceAddress == macAddress }?.first
    }
}