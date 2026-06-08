package com.example.firmwaremanagement.network

import com.example.firmwaremanagement.model.UpdateInfo
import com.example.firmwaremanagement.utils.SysPropUtils
import com.example.firmwaremanagement.utils.VersionUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed class UpdateCheckResult {
    data object NoUpdate : UpdateCheckResult()
    data class NewUpdate(val info: UpdateInfo) : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object UpdateChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun checkForUpdate(serverUrl: String): Result<UpdateInfo> {
        return try {
            val url = "$serverUrl/update_info.txt"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()
                ?: return Result.failure(Exception("Empty response body"))

            val info = UpdateInfo.parse(body)
                ?: return Result.failure(Exception("Failed to parse update info"))

            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun checkForUpdateWithVersion(serverUrl: String): UpdateCheckResult {
        return try {
            val currentVersion = SysPropUtils.getCurrentVersion()
            val result = checkForUpdate(serverUrl)

            result.fold(
                onSuccess = { info ->
                    if (VersionUtils.isNewerVersion(currentVersion, info.version)) {
                        UpdateCheckResult.NewUpdate(info)
                    } else {
                        UpdateCheckResult.NoUpdate
                    }
                },
                onFailure = { exception ->
                    UpdateCheckResult.Error(exception.message ?: "Unknown error")
                }
            )
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "Unknown error")
        }
    }
}
