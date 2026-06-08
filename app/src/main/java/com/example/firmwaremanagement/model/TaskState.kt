package com.example.firmwaremanagement.model

import org.json.JSONObject

data class TaskState(
    val taskId: String,
    val stage: Stage,
    val url: String,
    val targetFile: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val md5Expected: String,
    val headers: Array<String>,
    val pendingVersion: String,
    val errorMsg: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as TaskState

        if (taskId != other.taskId) return false
        if (stage != other.stage) return false
        if (url != other.url) return false
        if (targetFile != other.targetFile) return false
        if (downloadedBytes != other.downloadedBytes) return false
        if (totalBytes != other.totalBytes) return false
        if (md5Expected != other.md5Expected) return false
        if (!headers.contentEquals(other.headers)) return false
        if (pendingVersion != other.pendingVersion) return false
        if (errorMsg != other.errorMsg) return false

        return true
    }

    override fun hashCode(): Int {
        var result = taskId.hashCode()
        result = 31 * result + stage.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + targetFile.hashCode()
        result = 31 * result + downloadedBytes.hashCode()
        result = 31 * result + totalBytes.hashCode()
        result = 31 * result + md5Expected.hashCode()
        result = 31 * result + headers.contentHashCode()
        result = 31 * result + pendingVersion.hashCode()
        result = 31 * result + errorMsg.hashCode()
        return result
    }

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("taskId", taskId)
            put("stage", stage.name)
            put("url", url)
            put("targetFile", targetFile)
            put("downloadedBytes", downloadedBytes)
            put("totalBytes", totalBytes)
            put("md5Expected", md5Expected)
            put("headers", JSONObject().apply {
                headers.forEachIndexed { index, header ->
                    put(index.toString(), header)
                }
            })
            put("pendingVersion", pendingVersion)
            put("errorMsg", errorMsg)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TaskState {
            val headersArray = json.getJSONObject("headers")
            val headersList = mutableListOf<String>()
            headersArray.keys().forEach { key ->
                headersList.add(headersArray.getString(key))
            }

            return TaskState(
                taskId = json.getString("taskId"),
                stage = Stage.valueOf(json.getString("stage")),
                url = json.getString("url"),
                targetFile = json.getString("targetFile"),
                downloadedBytes = json.getLong("downloadedBytes"),
                totalBytes = json.getLong("totalBytes"),
                md5Expected = json.getString("md5Expected"),
                headers = headersList.toTypedArray(),
                pendingVersion = json.getString("pendingVersion"),
                errorMsg = json.getString("errorMsg")
            )
        }
    }
}
