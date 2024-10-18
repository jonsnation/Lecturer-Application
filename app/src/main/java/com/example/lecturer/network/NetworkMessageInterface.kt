package com.example.lecturer.network

import com.example.lecturer.models.ContentModel

interface NetworkMessageInterface {
    fun onContent(content: ContentModel)
}