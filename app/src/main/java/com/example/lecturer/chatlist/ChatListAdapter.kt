package com.example.lecturer.chatlist

import android.annotation.SuppressLint
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.lecturer.R
import com.example.lecturer.models.ContentModel

class ChatListAdapter : RecyclerView.Adapter<ChatListAdapter.ViewHolder>(){
    private val chatList:MutableList<ContentModel> = mutableListOf()

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val messageView: TextView = itemView.findViewById(R.id.messageTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.chat_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chat = chatList[position]
        (holder.messageView.parent as RelativeLayout).gravity = if (chat.senderIp=="192.168.49.1") Gravity.START else Gravity.END
        holder.messageView.text = chat.message

    }

    override fun getItemCount(): Int {
        return chatList.size
    }

    fun addItemToEnd(contentModel: ContentModel){
        chatList.add(contentModel)
        notifyItemInserted(chatList.size)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateChat(newChatList: List<ContentModel>) {
        chatList.clear()
        chatList.addAll(newChatList)
        notifyDataSetChanged()
    }


    @SuppressLint("NotifyDataSetChanged")
    fun clearChat() {
        chatList.clear()
        notifyDataSetChanged() // Notify that the data set has changed, so the UI is refreshed
    }

}