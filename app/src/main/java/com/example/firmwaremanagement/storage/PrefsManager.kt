package com.example.firmwaremanagement.storage

import android.content.Context
import android.content.SharedPreferences
import com.example.firmwaremanagement.utils.SysPropUtils

object PrefsManager {

    private const val PREFS_NAME = "ota_prefs"

    private const val KEY_SERVER_BASE_URL = "server_base_url"
    private const val KEY_PENDING_SLOT_VERSION = "pending_slot_version"
    private const val KEY_LAST_CHECK_TIME = "last_check_time"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getServerBaseUrl(): String? {
        return prefs.getString(KEY_SERVER_BASE_URL, null)
    }

    fun setServerBaseUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_BASE_URL, url).apply()
    }

    fun getPendingSlotVersion(): String? {
        return prefs.getString(KEY_PENDING_SLOT_VERSION, null)
    }

    fun setPendingSlotVersion(version: String) {
        prefs.edit().putString(KEY_PENDING_SLOT_VERSION, version).apply()
    }

    fun clearPendingSlotVersion() {
        prefs.edit().remove(KEY_PENDING_SLOT_VERSION).apply()
    }

    fun isServerConfigured(): Boolean {
        return !getServerBaseUrl().isNullOrBlank()
    }

    fun getCurrentVersion(): String {
        return SysPropUtils.getCurrentVersion()
    }

    fun getLastCheckTime(): Long {
        return prefs.getLong(KEY_LAST_CHECK_TIME, 0L)
    }

    fun setLastCheckTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_CHECK_TIME, time).apply()
    }
}
