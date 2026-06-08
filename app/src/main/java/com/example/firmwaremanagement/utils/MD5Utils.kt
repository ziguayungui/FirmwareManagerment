package com.example.firmwaremanagement.utils

import java.io.File
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest

object MD5Utils {

    private const val MD5_ALGORITHM = "MD5"

    /**
     * Calculate MD5 hash of entire file
     * @return hex string (32 characters lowercase)
     */
    fun calculateFileMD5(file: File): String {
        val digest = MessageDigest.getInstance(MD5_ALGORITHM)
        file.inputStream().use { inputStream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().toHexString()
    }

    /**
     * Calculate MD5 hash from input stream using DigestInputStream for streaming calculation
     * @param inputStream the input stream to calculate MD5
     * @param bufferSize the buffer size for reading (default 8192)
     * @return hex string (32 characters lowercase)
     */
    fun calculateStreamMD5(inputStream: InputStream, bufferSize: Int = 8192): String {
        val digest = MessageDigest.getInstance(MD5_ALGORITHM)
        val digestInputStream = DigestInputStream(inputStream, digest)
        val buffer = ByteArray(bufferSize)
        try {
            while (digestInputStream.read(buffer) != -1) {
                // DigestInputStream updates the digest automatically on read
            }
        } finally {
            digestInputStream.close()
        }
        return digest.digest().toHexString()
    }

    /**
     * Calculate MD5 hash of file portion for resume download verification
     * @param file the file to read
     * @param offset start position in file
     * @param length number of bytes to read
     * @return hex string (32 characters lowercase)
     */
    fun calculatePartialMD5(file: File, offset: Long, length: Long): String {
        val digest = MessageDigest.getInstance(MD5_ALGORITHM)
        file.inputStream().use { inputStream ->
            inputStream.skip(offset)
            var remaining = length
            val buffer = ByteArray(minOf(8192, length.toInt()))
            while (remaining > 0) {
                val bytesToRead = minOf(buffer.size.toLong(), remaining).toInt()
                val bytesRead = inputStream.read(buffer, 0, bytesToRead)
                if (bytesRead == -1) break
                digest.update(buffer, 0, bytesRead)
                remaining -= bytesRead
            }
        }
        return digest.digest().toHexString()
    }

    /**
     * Compare file MD5 with expected MD5
     * @param file the file to verify
     * @param expectedMD5 the expected MD5 hex string
     * @return true if match
     */
    fun verifyMD5(file: File, expectedMD5: String): Boolean {
        val actualMD5 = calculateFileMD5(file)
        return actualMD5.equals(expectedMD5, ignoreCase = true)
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}
