package com.example.lecturer.models

data class ContentModel(val message:String, var senderIp:String, var studentId:String? = null, var deviceAddress:String? = null, var timestamp:Long = System.currentTimeMillis()) {


}