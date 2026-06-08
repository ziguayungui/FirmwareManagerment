package com.example.firmwaremanagement.engine

interface UpdateEngineListener {
    fun onStatusUpdate(status: Int, percent: Float)
    fun onPayloadApplicationComplete(errorCode: Int)
    fun onPayloadApplicationSuccess()
    fun onPayloadApplicationError(errorCode: Int, message: String)
}

abstract class UpdateEngineCallbackAdapter {

    private var listener: UpdateEngineListener? = null

    fun setListener(listener: UpdateEngineListener?) {
        this.listener = listener
    }

    open fun onStatusUpdate(status: Int, percent: Float) {
        listener?.onStatusUpdate(status, percent)
    }

    open fun onPayloadApplicationComplete(errorCode: Int) {
        listener?.onPayloadApplicationComplete(errorCode)
        when (errorCode) {
            0 -> listener?.onPayloadApplicationSuccess()
            else -> listener?.onPayloadApplicationError(errorCode, getErrorMessage(errorCode))
        }
    }

    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            1 -> "Error parsing payload"
            2 -> "Payload apply error"
            3 -> "Payload signature error"
            4 -> "Payload timestamp error"
            else -> "Unknown error: $errorCode"
        }
    }

    companion object {
        const val STATUS_IDLE = 0
        const val STATUS_FETCHING = 1
        const val STATUS_VERIFICATION = 2
        const val STATUS_FINALIZATION = 3
        const val STATUS_ERROR = 5
        const val STATUS_UPDATED_NEED_REBOOT = 6
    }
}
