package com.example.firmwaremanagement.engine

object UpdateEngineWrapper {

    private var isBound = false
    private var callback: UpdateEngineCallbackAdapter? = null

    fun bind(callback: UpdateEngineCallbackAdapter): Boolean {
        this.callback = callback
        isBound = true
        return true
    }

    fun unbind() {
        isBound = false
        callback = null
    }

    fun applyPayload(fileUri: String, offset: Long, size: Long, headers: Array<String>) {
        // 模拟 UpdateEngine 的实现，或者实际通过反射调用系统 UpdateEngine
        // 这里我们暂时仅记录成功回调，实际项目中需要实现反射调用
        callback?.onPayloadApplicationComplete(0)
    }

    fun isBound(): Boolean = isBound
}
