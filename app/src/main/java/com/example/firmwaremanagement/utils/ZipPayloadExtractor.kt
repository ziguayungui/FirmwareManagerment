package com.example.firmwaremanagement.utils

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipFile

data class PayloadInfo(
    val offset: Long,
    val size: Long,
    val headers: Array<String>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PayloadInfo
        if (offset != other.offset) return false
        if (size != other.size) return false
        if (!headers.contentEquals(other.headers)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = offset.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + headers.contentHashCode()
        return result
    }
}

object ZipPayloadExtractor {

    fun extract(zipFilePath: String): PayloadInfo {
        val zipFile = ZipFile(zipFilePath)

        try {
            val payloadBinEntry = zipFile.getEntry("payload.bin")
                ?: throw IOException("Invalid OTA zip")

            val payloadPropertiesEntry = zipFile.getEntry("payload_properties.txt")
                ?: throw IOException("Invalid OTA zip")

            val dataOffsetMethod = payloadBinEntry::class.java.getMethod("getDataOffset")
            val offset = dataOffsetMethod.invoke(payloadBinEntry) as Long

            val size = payloadBinEntry.size

            val headers = BufferedReader(InputStreamReader(zipFile.getInputStream(payloadPropertiesEntry))).use { reader ->
                reader.lineSequence()
                    .filter { it.isNotBlank() }
                    .toList()
                    .toTypedArray()
            }

            return PayloadInfo(offset, size, headers)
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("Failed to get data offset", e)
        } finally {
            zipFile.close()
        }
    }
}
