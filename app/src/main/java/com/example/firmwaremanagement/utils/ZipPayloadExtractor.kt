package com.example.firmwaremanagement.utils

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class PayloadInfo(
    val payloadFile: String,
    val size: Long,
    val headers: Array<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PayloadInfo
        if (payloadFile != other.payloadFile) return false
        if (size != other.size) return false
        if (!headers.contentEquals(other.headers)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = payloadFile.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + headers.contentHashCode()
        return result
    }
}

object ZipPayloadExtractor {

    private const val TAG = "ZipPayloadExtractor"
    private const val EXTRACTED_PAYLOAD = "extracted_payload.bin"

    /**
     * 从 OTA ZIP 文件中提取 payload.bin，返回提取后的文件路径和信息
     */
    fun extract(context: Context, zipFilePath: String): PayloadInfo {
        Log.d(TAG, "extract: extracting payload from $zipFilePath")
        val zipFile = ZipFile(zipFilePath)

        try {
            val payloadBinEntry = zipFile.getEntry("payload.bin")
                ?: throw IOException("Invalid OTA zip: missing payload.bin")

            val payloadPropertiesEntry = zipFile.getEntry("payload_properties.txt")
                ?: throw IOException("Invalid OTA zip: missing payload_properties.txt")

            // 提取 payload.bin 到临时文件
            val extractDir = File(context.filesDir, "ota_extract")
            if (!extractDir.exists()) {
                extractDir.mkdirs()
            }
            val extractFile = File(extractDir, EXTRACTED_PAYLOAD)
            
            // 如果已存在则删除重新提取
            if (extractFile.exists()) {
                extractFile.delete()
            }

            Log.d(TAG, "extract: writing payload to ${extractFile.absolutePath}")
            val startTime = System.currentTimeMillis()
            
            BufferedInputStream(zipFile.getInputStream(payloadBinEntry)).use { input ->
                FileOutputStream(extractFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var totalRead = 0L
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        totalRead += read
                    }
                    Log.d(TAG, "extract: wrote $totalRead bytes in ${System.currentTimeMillis() - startTime}ms")
                }
            }

            val size = payloadBinEntry.size
            val actualSize = extractFile.length()
            Log.d(TAG, "extract: expected size=$size, actual=$actualSize")

            // 读取 payload_properties.txt
            val headers = zipFile.getInputStream(payloadPropertiesEntry).bufferedReader().use { reader ->
                reader.lineSequence()
                    .filter { it.isNotBlank() }
                    .toList()
                    .toTypedArray()
            }

            Log.d(TAG, "extract: done, payloadFile=${extractFile.absolutePath}, size=$actualSize, headers=${headers.size}")
            return PayloadInfo(extractFile.absolutePath, actualSize, headers)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("Failed to extract payload from OTA zip", e)
        } finally {
            zipFile.close()
        }
    }

    /**
     * 清理提取的临时文件
     */
    fun cleanup(context: Context) {
        val extractDir = File(context.filesDir, "ota_extract")
        val extractFile = File(extractDir, EXTRACTED_PAYLOAD)
        if (extractFile.exists()) {
            extractFile.delete()
            Log.d(TAG, "cleanup: deleted ${extractFile.absolutePath}")
        }
    }
}
