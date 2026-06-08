package com.example.firmwaremanagement.network

import android.util.Log
import com.example.firmwaremanagement.model.UpdateInfo
import com.example.firmwaremanagement.utils.SysPropUtils
import com.example.firmwaremanagement.utils.VersionUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateChecker"

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
        Log.d(TAG, "checkForUpdate: serverUrl='$serverUrl'")
        
        return try {
            val url = "$serverUrl/update_info.txt"
            Log.d(TAG, "Requesting URL: $url")
            
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "Response code: ${response.code}")
            
            val body = response.body?.string()
            Log.d(TAG, "Response body: ${body?.take(200)}...")
            
            if (body.isNullOrEmpty()) {
                Log.e(TAG, "Empty response body")
                return Result.failure(Exception("Empty response body"))
            }

            val info = UpdateInfo.parse(body)
            if (info == null) {
                Log.e(TAG, "Failed to parse update info")
                return Result.failure(Exception("Failed to parse update info"))
            }
            
            Log.d(TAG, "Parsed update info: version=${info.version}, filename=${info.filename}, md5=${info.md5}")
            Result.success(info)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update", e)
            Result.failure(e)
        }
    }

    fun checkForUpdateWithVersion(serverUrl: String): UpdateCheckResult {
        Log.d(TAG, "checkForUpdateWithVersion: serverUrl='$serverUrl'")
        
        return try {
            val currentVersion = SysPropUtils.getCurrentVersion()
            Log.d(TAG, "Current device version: '$currentVersion'")
            
            val result = checkForUpdate(serverUrl)

            result.fold(
                onSuccess = { info ->
                    Log.d(TAG, "Server version: ${info.version}")
                    
                    if (VersionUtils.isNewerVersion(currentVersion, info.version)) {
                        Log.d(TAG, "New version available!")
                        UpdateCheckResult.NewUpdate(info)
                    } else {
                        Log.d(TAG, "No update available (current >= server)")
                        UpdateCheckResult.NoUpdate
                    }
                },
                onFailure = { exception ->
                    Log.e(TAG, "Check update failed", exception)
                    UpdateCheckResult.Error(exception.message ?: "Unknown error")
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            UpdateCheckResult.Error(e.message ?: "Unknown error")
        }
    }
}