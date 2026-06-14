package com.PornAppApi

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Security
import java.security.spec.X509EncodedKeySpec
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher

object PornAppApi {
    private const val BASE_URL = "https://porn-app.com/api/v9"
    private const val PUBLIC_KEY_BASE64 = "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA2e/sX/U3UNOsCJQHkEK7IF+VG5D1jMSrel8NDYTKVhV5etQg8hW4Lo5wQckpB8mbDz9ZVgy1z647Csh/vSqnpT1Rb3F35xNERrz87WzeoVGqABDNU4l+yqREmjgeyUYyMgAlIGVzhXwZJjOcZl7zcGEuH0H24aZhiQX5XBfcG4Rugnc0QbxUbnblTJD2vNHn4nzEJbz0eBE81YyF/Wkc1P4a55lD3CzDMoqjGNgESyb+9AO2yhY3ux20k7RUkLg62B656kYIQjGBu7tSyLVL08htpQOs/GDpi31sB2a32NPzgj85TNIOXQQ5ZtOZHssYGADtbBKbREYR6mtKQKWG2qf58ns9wYZ2ATqwQAS/brTJYard0pThOh/71ik8aAeyw0jbL5jAhz0wSs679PwTUwvbD6oqd2w8MDr2YG4lyK7jPma1QqzMpKCn/N2YKOU0jjXcj/twaXKSUCr+LiCu7MxBl76j3WoyaI4FsPGXAKFPHQU6bixMY/0XmEezLzwlJ7cf24tLBqADm8ooy92xM6nfALY2bWMgAufqXwPRCfjlec/sOiSTO53P9XiaEUPLGpHUOqCZRFb1vc3v16B4Z1R+B6rYyaVJ9hkQ+x09yExDFxLQOuG7YqJkwq3az1CM0zhMtK48vJrBUkgLzVMWnt3Tycn73ZEINcR183XyFI0CAwEAAQ=="

    private var cachedHash = ""
    private var cachedHashTime = 0L

    init {
        Security.addProvider(BouncyCastleProvider())
    }

    private fun generateHash(): String {
        val ts = System.currentTimeMillis() / 1000
        if (ts - cachedHashTime < 30 && cachedHash.isNotEmpty()) return cachedHash

        val loginStatus = JsonObject()
        loginStatus.addProperty("pro", 0)
        loginStatus.addProperty("status", 0)
        loginStatus.addProperty("token", "")
        loginStatus.addProperty("unixtime", ts)
        loginStatus.addProperty("user_id", 0)

        val signatures = JsonArray()
        signatures.add("VQMyUhZdmnnwK5RVCbeGqu0HN020MEDUM44crQyL1zw=")

        val payload = JsonObject()
        payload.addProperty("id", "a1b2c3d4e5f6a7b8")
        payload.addProperty("isTV", false)
        payload.add("loginStatus", loginStatus)
        payload.addProperty("packageName", "com.streamdev.aiostreamer")
        payload.add("signatures", signatures)
        payload.addProperty("time", ts)
        payload.addProperty("version", 6643)

        val jsonStr = payload.toString()

        return try {
            val keyBytes = Base64.decode(PUBLIC_KEY_BASE64, Base64.DEFAULT)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
            val publicKey = keyFactory.generatePublic(keySpec)

            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding", BouncyCastleProvider.PROVIDER_NAME)
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val encrypted = cipher.doFinal(jsonStr.toByteArray(Charsets.UTF_8))

            cachedHash = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            cachedHashTime = ts
            cachedHash
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun gzip(data: String): ByteArray {
        val baos = ByteArrayOutputStream()
        GZIPOutputStream(baos).use { it.write(data.toByteArray(Charsets.UTF_8)) }
        return baos.toByteArray()
    }

    private fun httpPost(url: String, body: ByteArray, headers: Map<String, String>): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            for ((key, value) in headers) {
                conn.setRequestProperty(key, value)
            }
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode == 200) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    data class StreamResult(val url: String, val quality: Int)

    suspend fun getStreamUrls(
        siteTag: String,
        videoPageHtml: String,
        videoPageUrl: String
    ): List<StreamResult> = withContext(Dispatchers.IO) {
        val hash = generateHash()
        if (hash.isEmpty()) return@withContext emptyList()

        val videoObject = JsonObject()
        videoObject.addProperty("sourceLink", videoPageUrl)
        videoObject.addProperty("site", siteTag)

        val body = JsonObject()
        body.addProperty("payload", videoPageHtml)
        body.add("videoObject", videoObject)

        val reqHeaders = mapOf(
            "User-Agent" to "okhttp/5.3.2",
            "Authorization" to "Bearer ",
            "Content-Type" to "application/json; charset=UTF-8",
            "Content-Encoding" to "gzip",
            "hash" to hash
        )

        val responseText = httpPost("$BASE_URL/sites/$siteTag/stream?isTV=false", gzip(body.toString()), reqHeaders)
        if (responseText == null) return@withContext emptyList()

        try {
            val arr = JsonParser.parseString(responseText).asJsonArray
            arr.mapNotNull { obj ->
                val item = obj.asJsonObject
                val url = item.get("stream")?.asString ?: item.get("streamLink")?.asString ?: return@mapNotNull null
                if (url.isEmpty()) return@mapNotNull null
                val qualityStr = item.get("quality")?.asString ?: ""
                val quality = when {
                    qualityStr.contains("2160") || qualityStr.contains("4k") -> 2160
                    qualityStr.contains("1080") -> 1080
                    qualityStr.contains("720") -> 720
                    qualityStr.contains("480") -> 480
                    qualityStr.contains("360") -> 360
                    else -> 0
                }
                StreamResult(url, quality)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
}
