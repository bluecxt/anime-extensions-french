package fr.bluecxt.core.utils

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Attempts to detect the resolution (height) of an MP4 video by reading its header atoms.
 * Uses an HTTP Range request to only download the first 64KB, minimizing network overhead.
 * Strict timeout of 1.5 seconds ensures the UI never hangs waiting for this metadata.
 */
suspend fun OkHttpClient.detectMp4Resolution(url: String, headers: Headers): Int? {
    return withTimeoutOrNull(1500) {
        val requestHeaders = headers.newBuilder()
            .set("Range", "bytes=0-65535")
            .build()

        val response = try {
            newCall(GET(url, requestHeaders)).awaitSuccess()
        } catch (_: Exception) {
            return@withTimeoutOrNull null
        }

        if (!response.isSuccessful) {
            response.close()
            return@withTimeoutOrNull null
        }

        val bytes = response.use { it.body.bytes() }

        // We are looking for the "tkhd" (Track Header) atom which contains width and height.
        // The signature in bytes is [0x74, 0x6B, 0x68, 0x64] -> 't', 'k', 'h', 'd'
        val tkhdSignature = byteArrayOf(0x74, 0x6B, 0x68, 0x64)

        val index = indexOf(bytes, tkhdSignature)
        if (index == -1) return@withTimeoutOrNull null

        // In the tkhd atom, the height is located at an offset of 84 bytes from the start of the atom data.
        // Since our index points to the signature, the height data starts at index + 84.
        // The height is a 32-bit fixed-point number (16.16), so we only care about the upper 16 bits (2 bytes).
        val heightOffset = index + 84

        if (heightOffset + 1 >= bytes.size) return@withTimeoutOrNull null

        // Read 16-bit integer (big-endian)
        val height = ((bytes[heightOffset].toInt() and 0xFF) shl 8) or (bytes[heightOffset + 1].toInt() and 0xFF)

        if (height in 240..2160) {
            height
        } else {
            null
        }
    }
}

/**
 * Helper to find a byte sequence within an array (like indexOf for String).
 */
private fun indexOf(source: ByteArray, target: ByteArray): Int {
    if (target.isEmpty()) return 0
    if (source.size < target.size) return -1

    for (i in 0..source.size - target.size) {
        var found = true
        for (j in target.indices) {
            if (source[i + j] != target[j]) {
                found = false
                break
            }
        }
        if (found) return i
    }
    return -1
}
