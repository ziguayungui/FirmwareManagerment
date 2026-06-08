package com.example.firmwaremanagement.utils

object VersionUtils {

    fun compareVersions(version1: String, version2: String): Int {
        val v1Parts = parseVersion(version1)
        val v2Parts = parseVersion(version2)

        val maxLength = maxOf(v1Parts.size, v2Parts.size)

        for (i in 0 until maxLength) {
            val v1 = v1Parts.getOrElse(i) { 0 }
            val v2 = v2Parts.getOrElse(i) { 0 }

            when {
                v1 < v2 -> return -1
                v1 > v2 -> return 1
            }
        }

        return 0
    }

    fun isNewerVersion(currentVersion: String, newVersion: String): Boolean {
        return compareVersions(currentVersion, newVersion) < 0
    }

    fun isOlderVersion(currentVersion: String, oldVersion: String): Boolean {
        return compareVersions(currentVersion, oldVersion) > 0
    }

    private fun parseVersion(version: String): List<Int> {
        val numericOnly = version.replace(Regex("^[^0-9]*"), "")
        return numericOnly.split(".")
            .filter { it.isNotEmpty() }
            .map { it.toIntOrNull() ?: 0 }
    }
}
