package com.example.firmwaremanagement.utils

object SysPropUtils {

    fun getCurrentVersion(): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, "ro.build.version.incremental") as String
        } catch (e: Exception) {
            ""
        }
    }

    fun getDeviceCodename(): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, "ro.product.device") as String
        } catch (e: Exception) {
            ""
        }
    }
}
