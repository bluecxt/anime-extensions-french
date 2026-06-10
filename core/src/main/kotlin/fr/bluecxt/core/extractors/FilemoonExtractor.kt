package fr.bluecxt.core.extractors

import android.util.Base64
import android.util.Log
import fr.bluecxt.core.FILEMOON_LOG
import fr.bluecxt.core.model.ExtractedSource
import fr.bluecxt.core.utils.PlaylistUtils
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

// based on https://github.com/skoruppa/docchi-players/blob/main/filemoon.py

class FilemoonExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    fun videosFromUrl(url: String, headers: Headers? = null): List<ExtractedSource> {
        try {
            Log.d(FILEMOON_LOG, "🚀 [START] Extraction (Programmatic): $url")

            val userAgent = headers?.get("User-Agent") ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

            val initialHeaders = (headers?.newBuilder() ?: Headers.Builder())
                .set("User-Agent", userAgent)
                .build()

            // 1. Resolve actual host and mediaId
            val initialResponse = client.newCall(Request.Builder().url(url).headers(initialHeaders).build()).execute()
            val resolvedUrl = initialResponse.request.url
            var host = resolvedUrl.host

            // Redirect domains (static list from Python)
            if (host in REDIRECT_DOMAINS || host == "filemoon.to") {
                host = "streamlyplayer.online"
            }

            val mediaId = Regex("/(?:e|eyi|d|download|j\\d+)/([0-9a-zA-Z]+)").find(resolvedUrl.toString())?.groupValues?.get(1)
                ?: return emptyList()

            val apiHeaders = initialHeaders.newBuilder()
                .set("Referer", url)
                .set("Origin", "https://$host")
                .build()

            // 2. Try flows
            var playbackData: PlaybackResponse? = null

            // Try Legacy Flow first
            try {
                Log.d(FILEMOON_LOG, "🔄 Tentative Legacy Flow...")
                playbackData = doLegacyFlow(host, mediaId, apiHeaders)
            } catch (e: Exception) {
                Log.d(FILEMOON_LOG, "⚠️ Legacy Flow failed: ${e.message}")
            }

            if (playbackData == null || (playbackData.sources.isNullOrEmpty() && playbackData.playback == null)) {
                try {
                    Log.d(FILEMOON_LOG, "🔄 Tentative Challenge Flow...")
                    playbackData = doChallengeFlow(host, mediaId, apiHeaders, resolvedUrl.toString())
                } catch (e: Exception) {
                    Log.e(FILEMOON_LOG, "❌ Échec de tous les flows: ${e.message}")
                    return emptyList()
                }
            }

            // 3. Extract sources
            val sources = mutableListOf<Source>()
            playbackData?.sources?.let { sources.addAll(it) }

            playbackData?.playback?.let {
                try {
                    val decrypted = decryptPlayback(it)
                    val decryptedJson = json.decodeFromString<PlaybackResponse>(decrypted)
                    decryptedJson.sources?.let { sources.addAll(it) }
                } catch (e: Exception) {
                    Log.e(FILEMOON_LOG, "❌ Erreur décryptage playback: ${e.message}")
                }
            }

            if (sources.isEmpty()) return emptyList()

            val streamUrl = sources.first().url.let {
                if (it.startsWith("/")) "https://$host$it" else it
            }

            Log.d(FILEMOON_LOG, "✅ [SUCCESS] Flux trouvé: $streamUrl")

            val finalHeaders = apiHeaders.newBuilder()
                .set("User-Agent", userAgent)
                .set("Referer", "https://$host/")
                .build()

            return playlistUtils.extractFromHls(
                playlistUrl = streamUrl,
                referer = "https://$host/",
                masterHeaders = finalHeaders,
                videoHeaders = finalHeaders,
            )
        } catch (e: Exception) {
            Log.e(FILEMOON_LOG, "❌ [ERROR] Extraction failed: ${e.message}")
            return emptyList()
        }
    }

    private fun doLegacyFlow(host: String, mediaId: String, headers: Headers): PlaybackResponse {
        val apiUrl = "https://$host/api/videos/$mediaId/playback"
        val fingerprint = buildLegacyFingerprint()

        val payload = mapOf("fingerprint" to fingerprint)
        val request = Request.Builder()
            .url(apiUrl)
            .headers(headers)
            .post(json.encodeToString(payload).toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        return response.parseAs()
    }

    private fun doChallengeFlow(host: String, mediaId: String, headers: Headers, embedUrl: String): PlaybackResponse {
        val base = "https://$host"
        val userAgent = headers["User-Agent"] ?: ""

        val refererHost = headers["Referer"]?.toHttpUrlOrNull()?.host ?: "filemoon.sx"

        val apiHeaders = headers.newBuilder()
            .set("Accept", "*/*")
            .set("Origin", base)
            .set("Referer", embedUrl)
            .build()

        // 1. Challenge
        val challengeUrl = "$base/api/videos/access/challenge"
        val challengeResponse = client.newCall(
            Request.Builder().url(challengeUrl).headers(apiHeaders).post("".toRequestBody()).build(),
        ).execute().parseAs<ChallengeResponse>()

        // 2. Attest
        val (privateKey, jwk) = generateEcKeypair()
        val signature = signNonce(privateKey, challengeResponse.nonce)
        val clientFp = generateClientFingerprint(userAgent)

        val attestPayload = AttestPayload(
            challengeId = challengeResponse.challengeId,
            nonce = challengeResponse.nonce,
            signature = signature,
            publicKey = jwk,
            client = clientFp,
        )

        val attestUrl = "$base/api/videos/access/attest"
        val attestResponse = client.newCall(
            Request.Builder().url(attestUrl).headers(apiHeaders).post(json.encodeToString(attestPayload).toRequestBody(jsonMediaType)).build(),
        ).execute().parseAs<AttestResponse>()

        val fp = Fingerprint(
            token = attestResponse.token,
            viewerId = attestResponse.viewerId,
            deviceId = attestResponse.deviceId,
            confidence = attestResponse.confidence,
        )

        // 3. Captcha
        val captchaUrl = "$base/api/videos/$mediaId/embed/captcha"
        val embedHeaders = apiHeaders.newBuilder()
            .set("X-Embed-Origin", refererHost)
            .set("X-Embed-Referer", "https://$refererHost/")
            .set("X-Embed-Parent", embedUrl)
            .set("Cookie", "byse_viewer_id=${attestResponse.viewerId}; byse_device_id=${attestResponse.deviceId}")
            .build()

        val captchaResponse = client.newCall(
            Request.Builder().url(captchaUrl).headers(embedHeaders).post(json.encodeToString(CaptchaPayload(fp)).toRequestBody(jsonMediaType)).build(),
        ).execute().parseAs<CaptchaResponse>()

        // 4. Solve PoW
        val solution = solvePow(captchaResponse.powNonce, captchaResponse.powDifficulty)

        val verifyUrl = "$base/api/videos/$mediaId/embed/captcha/verify"
        val verifyPayload = VerifyPayload(
            powToken = captchaResponse.powToken,
            solution = solution,
            fingerprint = fp,
        )
        val verifyResponse = client.newCall(
            Request.Builder().url(verifyUrl).headers(embedHeaders).post(json.encodeToString(verifyPayload).toRequestBody(jsonMediaType)).build(),
        ).execute().parseAs<VerifyResponse>()

        if (verifyResponse.status != "ok" || verifyResponse.token == null) {
            throw Exception("PoW verification failed: ${verifyResponse.status}")
        }

        // 5. Playback
        val playbackUrl = "$base/api/videos/$mediaId/embed/playback"
        val playbackHeaders = embedHeaders.newBuilder()
            .set("X-Captcha-Token", verifyResponse.token)
            .build()

        return client.newCall(
            Request.Builder().url(playbackUrl).headers(playbackHeaders).post(json.encodeToString(CaptchaPayload(fp)).toRequestBody(jsonMediaType)).build(),
        ).execute().parseAs()
    }

    private fun decryptPlayback(playback: PlaybackData): String {
        val iv = ft(playback.iv)
        val key = xn(playback.keyParts, playback.version)
        val payload = ft(playback.payload)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)

        return String(cipher.doFinal(payload), Charsets.ISO_8859_1)
    }

    private fun xn(e: List<String>, version: String?): ByteArray {
        var parts = e
        if (version != null) {
            val v = version.toInt()
            parts = listOf(e[v - 1], e[e.size - v])
        }
        return parts.map { ft(it) }.reduce { acc, bytes -> acc + bytes }
    }

    private fun ft(e: String): ByteArray {
        var t = e.replace("-", "+").replace("_", "/")
        val r = if (t.length % 4 == 0) 0 else 4 - t.length % 4
        t += "=".repeat(r)
        return Base64.decode(t, Base64.DEFAULT)
    }

    private fun generateEcKeypair(): Pair<PrivateKey, Jwk> {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        val publicKey = kp.public as ECPublicKey

        val x = publicKey.w.affineX.toByteArray().normalize(32)
        val y = publicKey.w.affineY.toByteArray().normalize(32)

        val jwk = Jwk(
            x = Base64.encodeToString(x, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
            y = Base64.encodeToString(y, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING),
        )
        return Pair(kp.private, jwk)
    }

    private fun signNonce(privateKey: PrivateKey, nonce: String): String {
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(nonce.toByteArray())
        val signatureBytes = sig.sign()
        return Base64.encodeToString(signatureBytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun solvePow(nonce: String, difficulty: Int, maxIterations: Int = 200000): String {
        val bufferSize = 512
        val bufferMask = 511
        val initConst = 2654435761L
        val finalConst = 2246822519L
        val mask32 = 0xFFFFFFFFL

        fun rl(val_: Long, shift: Int): Long = ((val_ shl shift) or (val_ ushr (32 - shift))) and mask32

        val prefix = "$nonce:".toByteArray(Charsets.ISO_8859_1)

        for (counter in 0 until maxIterations) {
            val counterBytes = counter.toString().toByteArray(Charsets.ISO_8859_1)
            val inputBytes = prefix + counterBytes

            var s0 = 1779033703L
            var s1 = 3144134277L
            var s2 = 1013904242L
            var s3 = 2773480762L

            for (b in inputBytes) {
                val bLong = b.toLong() and 0xFFL
                s0 = (s0 + bLong) and mask32
                s0 = rl(s0, 7)
                s0 = (s0 + s1) and mask32
                s3 = rl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rl(s1 xor s2, 7)
            }

            repeat(8) {
                s0 = (s0 + s1) and mask32
                s3 = rl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rl(s1 xor s2, 7)
            }

            val buf = LongArray(bufferSize)
            for (i in 0 until bufferSize) {
                s0 = (s0 + s1) and mask32
                s3 = rl(s3 xor s0, 16)
                s2 = (s2 + s3) and mask32
                s1 = rl(s1 xor s2, 12)
                s0 = (s0 + s1) and mask32
                s3 = rl(s3 xor s0, 8)
                s2 = (s2 + s3) and mask32
                s1 = rl(s1 xor s2, 7)
                buf[i] = (s0 xor s2) and mask32
            }

            repeat(2) {
                for (si in 0 until bufferSize) {
                    val a = (buf[si] and bufferMask.toLong()).toInt()
                    var c = (buf[si] + buf[a]) and mask32
                    c = rl(c, 13)
                    c = (c xor ((buf[(si + 1) and bufferMask] * initConst) and mask32)) and mask32
                    buf[si] = c
                    s0 = (s0 xor c) and mask32
                    s0 = (s0 + s1) and mask32
                    s3 = rl(s3 xor s0, 16)
                    s2 = (s2 + s3) and mask32
                    s1 = rl(s1 xor s2, 12)
                    s0 = (s0 + s1) and mask32
                    s3 = rl(s3 xor s0, 8)
                    s2 = (s2 + s3) and mask32
                    s1 = rl(s1 xor s2, 7)
                }
            }

            s0 = (s0 + s1) and mask32
            s3 = rl(s3 xor s0, 16)
            s2 = (s2 + s3) and mask32
            s1 = rl(s1 xor s2, 12)
            s0 = (s0 + s1) and mask32
            s3 = rl(s3 xor s0, 8)
            s2 = (s2 + s3) and mask32
            s1 = rl(s1 xor s2, 7)

            var outVal = s0
            for (ci in 0 until 64) {
                val d = buf[ci]
                outVal = (outVal + d) and mask32
                outVal = rl(outVal, 5)
                outVal = (outVal xor ((d * finalConst) and mask32)) and mask32
            }
            outVal = (outVal xor s2) and mask32

            val actualLeading = if (outVal > 0) java.lang.Long.numberOfLeadingZeros(outVal) - 32 else 32

            if (actualLeading >= difficulty) {
                return counter.toString()
            }
        }
        throw Exception("PoW solver: no solution found in $maxIterations iterations")
    }

    private fun generateClientFingerprint(userAgent: String): ClientFingerprint {
        val screenWidths = listOf(1920, 2560, 1366, 1440, 1680)
        val screenHeights = listOf(1080, 1440, 768, 900, 1050)
        val idx = Random.nextInt(screenWidths.size)

        return ClientFingerprint(
            userAgent = userAgent,
            pixelRatio = listOf(1, 2).random(),
            screenWidth = screenWidths[idx],
            screenHeight = screenHeights[idx],
            colorDepth = 24,
            languages = listOf("fr-FR", "fr", "en-US", "en"),
            timezone = "Europe/Paris",
            hardwareConcurrency = listOf(4, 8, 12, 16).random(),
            touchPoints = 0,
            webglVendor = listOf("Intel", "NVIDIA Corporation", "AMD").random(),
            webglRenderer = listOf(
                "Intel(R) HD Graphics",
                "NVIDIA GeForce GTX 1070",
                "Mesa Intel(R) UHD Graphics 620",
            ).random(),
            canvasHash = randomB64Url(32),
            audioHash = randomB64Url(32),
            webglParamsHash = randomB64Url(32),
            fontsHash = randomB64Url(32),
            codecsHash = randomB64Url(32),
            mediaDevices = "ai0ao0vi0",
            pointerType = "fine,hover",
            extra = mapOf("vendor" to "", "appVersion" to "5.0 (X11)"),
        )
    }

    private fun buildLegacyFingerprint(): Map<String, JsonElement> {
        val vId = Random.nextBytes(16).toHex()
        val dId = Random.nextBytes(16).toHex()
        val ctime = System.currentTimeMillis() / 1000

        val tData = mutableMapOf<String, JsonElement>(
            "viewer_id" to JsonPrimitive(vId),
            "device_id" to JsonPrimitive(dId),
            "confidence" to JsonPrimitive(Random.nextDouble(0.6, 0.9)),
            "iat" to JsonPrimitive(ctime),
            "exp" to JsonPrimitive(ctime + 600),
        )

        val tBdata = Base64.encodeToString(json.encodeToString(tData).toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val md = MessageDigest.getInstance("SHA-256")
        val tSig = Base64.encodeToString(md.digest(tBdata.toByteArray()), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val token = "$tBdata.$tSig"

        tData["token"] = JsonPrimitive(token)
        tData.remove("iat")
        tData.remove("exp")

        return tData
    }

    private fun randomB64Url(size: Int): String {
        val bytes = ByteArray(size)
        Random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun ByteArray.normalize(size: Int): ByteArray = if (this.size > size) {
        this.copyOfRange(this.size - size, this.size)
    } else if (this.size < size) {
        val res = ByteArray(size)
        System.arraycopy(this, 0, res, size - this.size, this.size)
        res
    } else {
        this
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private inline fun <reified T> Response.parseAs(): T {
        val bodyStr = body.string()
        if (!isSuccessful) {
            throw Exception("HTTP $code: $bodyStr")
        }
        return json.decodeFromString(bodyStr)
    }

    private fun String.toHttpUrlOrNull() = try {
        toHttpUrl()
    } catch (e: Exception) {
        null
    }

    @Serializable
    private data class ChallengeResponse(
        @SerialName("challenge_id") val challengeId: String,
        val nonce: String,
    )

    @Serializable
    private data class AttestPayload(
        @SerialName("viewer_id") val viewerId: String = "",
        @SerialName("device_id") val deviceId: String = "",
        @SerialName("challenge_id") val challengeId: String,
        val nonce: String,
        val signature: String,
        @SerialName("public_key") val publicKey: Jwk,
        val client: ClientFingerprint,
        val storage: Map<String, String> = emptyMap(),
        val attributes: Map<String, String> = mapOf("entropy" to "low"),
    )

    @Serializable
    private data class Jwk(
        val alg: String = "ES256",
        val crv: String = "P-256",
        val ext: Boolean = true,
        @SerialName("key_ops") val keyOps: List<String> = listOf("verify"),
        val kty: String = "EC",
        val x: String,
        val y: String,
    )

    @Serializable
    private data class ClientFingerprint(
        @SerialName("user_agent") val userAgent: String,
        @SerialName("pixel_ratio") val pixelRatio: Int,
        @SerialName("screen_width") val screenWidth: Int,
        @SerialName("screen_height") val screenHeight: Int,
        @SerialName("color_depth") val colorDepth: Int,
        val languages: List<String>,
        val timezone: String,
        @SerialName("hardware_concurrency") val hardwareConcurrency: Int,
        @SerialName("touch_points") val touchPoints: Int,
        @SerialName("webgl_vendor") val webglVendor: String,
        @SerialName("webgl_renderer") val webglRenderer: String,
        @SerialName("canvas_hash") val canvasHash: String,
        @SerialName("audio_hash") val audioHash: String,
        @SerialName("webgl_params_hash") val webglParamsHash: String,
        @SerialName("fonts_hash") val fontsHash: String,
        @SerialName("codecs_hash") val codecsHash: String,
        @SerialName("media_devices") val mediaDevices: String,
        @SerialName("pointer_type") val pointerType: String,
        val extra: Map<String, String>,
    )

    @Serializable
    private data class AttestResponse(
        val token: String,
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        val confidence: Double,
    )

    @Serializable
    private data class CaptchaPayload(
        val fingerprint: Fingerprint,
    )

    @Serializable
    private data class Fingerprint(
        val token: String,
        @SerialName("viewer_id") val viewerId: String,
        @SerialName("device_id") val deviceId: String,
        val confidence: Double,
    )

    @Serializable
    private data class CaptchaResponse(
        @SerialName("pow_nonce") val powNonce: String,
        @SerialName("pow_difficulty") val powDifficulty: Int,
        @SerialName("pow_token") val powToken: String,
    )

    @Serializable
    private data class VerifyPayload(
        @SerialName("pow_token") val powToken: String,
        val solution: String,
        val fingerprint: Fingerprint,
    )

    @Serializable
    private data class VerifyResponse(
        val status: String,
        val token: String? = null,
    )

    @Serializable
    private data class PlaybackResponse(
        val sources: List<Source>? = null,
        val playback: PlaybackData? = null,
    )

    @Serializable
    private data class Source(
        val url: String,
    )

    @Serializable
    private data class PlaybackData(
        val iv: String,
        @SerialName("key_parts") val keyParts: List<String>,
        val version: String? = null,
        val payload: String,
    )

    companion object {
        private val REDIRECT_DOMAINS = listOf("boosteradx.online", "byse.sx")
    }
}
