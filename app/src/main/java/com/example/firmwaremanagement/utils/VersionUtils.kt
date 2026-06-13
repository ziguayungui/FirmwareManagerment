package com.example.firmwaremanagement.utils

import android.util.Log

object VersionUtils {

    private const val TAG = "VersionUtils"
    
    private val VERSION_PATTERN = Regex("^R_\\d\\.\\d\\.\\d$")

    fun compareVersions(version1: String, version2: String): Int {
        Log.d(TAG, "compareVersions: v1='$version1', v2='$version2'")
        
        val v1Parts = parseVersion(version1)
        val v2Parts = parseVersion(version2)
        
        Log.d(TAG, "Parsed versions: v1=$v1Parts, v2=$v2Parts")

        val maxLength = maxOf(v1Parts.size, v2Parts.size)

        for (i in 0 until maxLength) {
            val v1 = v1Parts.getOrElse(i) { 0 }
            val v2 = v2Parts.getOrElse(i) { 0 }

            when {
                v1 < v2 -> {
                    Log.d(TAG, "v1 < v2, returning -1")
                    return -1
                }
                v1 > v2 -> {
                    Log.d(TAG, "v1 > v2, returning 1")
                    return 1
                }
            }
        }

        Log.d(TAG, "Versions are equal, returning 0")
        return 0
    }

    fun isNewerVersion(currentVersion: String, newVersion: String): Boolean {
        if (currentVersion.isBlank()) {
            Log.w(TAG, "Current version is blank, assuming newer version available")
            return true
        }
        if (newVersion.isBlank()) {
            Log.w(TAG, "New version is blank")
            return false
        }
        
        if (!isValidVersion(newVersion)) {
            Log.w(TAG, "New version '$newVersion' is not valid (must be Vx.x.x)")
            return false
        }
        
        if (!isValidVersion(currentVersion)) {
            Log.w(TAG, "Current version '$currentVersion' is not valid, assuming newer version available")
            return true
        }
        
        val result = compareVersions(currentVersion, newVersion)
        Log.d(TAG, "isNewerVersion: current='$currentVersion', new='$newVersion', result=$result")
        return result < 0
    }

    fun isOlderVersion(currentVersion: String, oldVersion: String): Boolean {
        return compareVersions(currentVersion, oldVersion) > 0
    }

    fun isValidVersion(version: String): Boolean {
        val isValid = VERSION_PATTERN.matches(version)
        Log.d(TAG, "isValidVersion: '$version' -> $isValid")
        return isValid
    }

    private fun parseVersion(version: String): List<Int> {
        Log.d(TAG, "parseVersion: input='$version'")
        
        if (!isValidVersion(version)) {
            Log.w(TAG, "Invalid version format, extracting numeric parts")
        }
        
        val numericOnly = version.replace(Regex("^[^0-9]*"), "")
        Log.d(TAG, "numericOnly: '$numericOnly'")
        
        val parts = numericOnly.split(".")
            .filter { it.isNotEmpty() }
            .map { 
                val parsed = it.toIntOrNull() ?: 0
                Log.d(TAG, "  part '$it' -> $parsed")
                parsed 
            }
        
        Log.d(TAG, "Parsed to: $parts")
        return parts
    }
}