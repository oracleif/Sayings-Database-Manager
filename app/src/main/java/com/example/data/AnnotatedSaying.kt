package com.example.data

data class AnnotatedSaying(
    val content: String,
    val annotation: String?
) {
    val displayFormatted: String
        get() = if (annotation.isNullOrBlank()) content else "$content\n[$annotation]"
}
