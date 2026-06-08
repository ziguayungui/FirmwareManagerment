package com.example.firmwaremanagement.model

data class UpdateInfo(
    val version: String,
    val md5: String,
    val filename: String
) {
    companion object {
        fun parse(text: String): UpdateInfo? {
            return try {
                val lines = text.trim().split("\n")
                val map = lines.associate { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        parts[0].trim() to parts[1].trim()
                    } else {
                        parts[0].trim() to ""
                    }
                }
                UpdateInfo(
                    version = map["version"] ?: return null,
                    md5 = map["md5"] ?: return null,
                    filename = map["filename"] ?: return null
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
