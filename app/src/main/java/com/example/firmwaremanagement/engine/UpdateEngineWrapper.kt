package com.example.firmwaremanagement.engine

import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.lang.reflect.Constructor

object UpdateEngineWrapper {

    private const val TAG = "UpdateEngineWrapper"
    private var engine: Any? = null
    private var engineClass: Class<*>? = null
    private var callback: UpdateEngineCallbackAdapter? = null

    fun bind(callback: UpdateEngineCallbackAdapter): Boolean {
        this.callback = callback
        try {
            engineClass = Class.forName("android.os.UpdateEngine")
            engine = engineClass!!.getDeclaredConstructor().newInstance()
            Log.d(TAG, "bind: UpdateEngine created successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "bind: failed to create UpdateEngine: ${e.message}")
            engine = null
            engineClass = null
            return false
        }
    }

    fun unbind() {
        engine = null
        engineClass = null
        callback = null
        Log.d(TAG, "unbind: UpdateEngine released")
    }

    fun applyPayload(fileUri: String, offset: Long, size: Long, headers: Array<String>) {
        val eng = engine ?: run {
            Log.e(TAG, "applyPayload: UpdateEngine not initialized")
            callback?.onPayloadApplicationComplete(1)
            return
        }

        Log.d(TAG, "applyPayload: fileUri=$fileUri, offset=$offset, size=$size")

        try {
            val engineCls = engineClass!!

            // 尝试调用 applyPayload(String, long, long, String[])
            try {
                val method = engineCls.getMethod(
                    "applyPayload",
                    String::class.java,
                    Long::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType,
                    Array<String>::class.java
                )
                method.invoke(eng, fileUri, offset, size, headers)
                Log.d(TAG, "applyPayload: called via string overload")
            } catch (e: NoSuchMethodException) {
                // 尝试调用 applyPayload(ParcelFileDescriptor, long, long, String[])
                try {
                    val file = File(fileUri.replace("file://", ""))
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val method = engineCls.getMethod(
                        "applyPayload",
                        ParcelFileDescriptor::class.java,
                        Long::class.javaPrimitiveType,
                        Long::class.javaPrimitiveType,
                        Array<String>::class.java
                    )
                    method.invoke(eng, pfd, offset, size, headers)
                    Log.d(TAG, "applyPayload: called via ParcelFileDescriptor overload")
                } catch (e2: Exception) {
                    Log.e(TAG, "applyPayload: all overloads failed: ${e2.message}")
                    callback?.onPayloadApplicationComplete(1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyPayload: unexpected error: ${e.message}")
            callback?.onPayloadApplicationComplete(1)
        }
    }

    fun bindListener(): Boolean {
        val eng = engine ?: return false
        val engineCls = engineClass ?: return false
        val cb = callback ?: return false

        try {
            // android.os.UpdateEngine 使用 UpdateEngineCallback
            // 需要通过 Binder 机制注册 callback
            val callbackClass = Class.forName("android.os.UpdateEngineCallback")

            // 创建动态代理
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { _, method, args ->
                when (method.name) {
                    "onStatusUpdate" -> {
                        val status = args[0] as Int
                        val percent = args[1] as Float
                        Log.d(TAG, "onStatusUpdate: status=$status, percent=$percent")
                        cb.onStatusUpdate(status, percent)
                    }
                    "onPayloadApplicationComplete" -> {
                        val errorCode = args[0] as Int
                        Log.d(TAG, "onPayloadApplicationComplete: errorCode=$errorCode")
                        cb.onPayloadApplicationComplete(errorCode)
                    }
                }
                null
            }

            val method = engineCls.getMethod("bind", callbackClass)
            method.invoke(eng, proxy)
            Log.d(TAG, "bindListener: callback bound successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "bindListener: failed: ${e.message}")
            // 如果绑定失败，通过手动回调通知
            // 但这里不立即回调，让 applyPayload 完成后通过异步方式通知
            return false
        }
    }

    fun isBound(): Boolean = engine != null
}
