package com.example.firmwaremanagement.storage

import android.content.Context
import com.example.firmwaremanagement.model.Stage
import com.example.firmwaremanagement.model.TaskState
import org.json.JSONObject
import java.io.File

object TaskStateManager {

    private const val TASK_STATE_FILE = "task_state.json"

    private fun getTaskStateFile(context: Context): File {
        return File(context.filesDir, TASK_STATE_FILE)
    }

    fun saveTaskState(context: Context, state: TaskState) {
        val file = getTaskStateFile(context)
        file.writeText(state.toJson().toString())
    }

    fun loadTaskState(context: Context): TaskState? {
        val file = getTaskStateFile(context)
        if (!file.exists()) {
            return null
        }
        return try {
            val json = JSONObject(file.readText())
            TaskState.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    fun clearTaskState(context: Context) {
        val file = getTaskStateFile(context)
        if (file.exists()) {
            file.delete()
        }
    }

    fun getCurrentStage(context: Context): Stage {
        val state = loadTaskState(context)
        return state?.stage ?: Stage.IDLE
    }

    fun isTaskResumable(context: Context): Boolean {
        val state = loadTaskState(context)
        return state != null && state.stage == Stage.DOWNLOADING && state.downloadedBytes > 0
    }

    fun updateStage(context: Context, stage: Stage) {
        val state = loadTaskState(context)
        if (state != null) {
            val updatedState = state.copy(stage = stage)
            saveTaskState(context, updatedState)
        }
    }

    fun setError(context: Context, errorMsg: String) {
        val state = loadTaskState(context)
        if (state != null) {
            val updatedState = state.copy(stage = Stage.ERROR, errorMsg = errorMsg)
            saveTaskState(context, updatedState)
        }
    }

    fun reset(context: Context) {
        clearTaskState(context)
    }
}
