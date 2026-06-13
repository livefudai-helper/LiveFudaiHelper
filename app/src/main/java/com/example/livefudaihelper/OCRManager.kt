package com.example.livefudaihelper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object OCRManager {

    private const val TAG = "OCRManager"

    // 百度OCR API配置
    private const val API_KEY = "LfAuoZ7ESwrdb4uYHELPEskB"
    private const val SECRET_KEY = "zM48dwXfX01RE6idOOASnCswD0KoSX15"
    private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
    private const val OCR_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic"

    private var accessToken: String? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 获取Access Token
     */
    private fun getAccessToken(): String? {
        if (!accessToken.isNullOrEmpty()) {
            return accessToken
        }

        return try {
            val url = "$TOKEN_URL?grant_type=client_credentials" +
                    "&client_id=$API_KEY" +
                    "&client_secret=$SECRET_KEY"

            val request = Request.Builder()
                .url(url)
                .post(RequestBody.create(null, ByteArray(0)))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (body != null) {
                val json = JSONObject(body)
                accessToken = json.getString("access_token")
                Log.d(TAG, "获取Token成功: $accessToken")
                accessToken
            } else {
                Log.e(TAG, "获取Token失败: 响应为空")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取Token异常", e)
            null
        }
    }

    /**
     * 识别图片中的文字
     */
    fun recognizeText(bitmap: Bitmap): String? {
        val token = getAccessToken()
        if (token == null) {
            Log.e(TAG, "Token获取失败，无法识别")
            return null
        }

        return try {
            // 压缩图片
            val compressed = compressBitmap(bitmap, 1024)

            // 转Base64
            val baos = ByteArrayOutputStream()
            compressed.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val imageBytes = baos.toByteArray()
            val base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT)

            // 构建请求
            val url = "$OCR_URL?access_token=$token"
            val formBody = FormBody.Builder()
                .add("image", base64Image)
                .add("language_type", "CHN_ENG")
                .build()

            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            if (body != null) {
                parseOCRResult(body)
            } else {
                Log.e(TAG, "OCR识别失败: 响应为空")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "OCR识别异常", e)
            null
        }
    }

    /**
     * 压缩Bitmap
     */
    private fun compressBitmap(bitmap: Bitmap, maxWidth: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxWidth) {
            return bitmap
        }

        val ratio = maxWidth.toFloat() / width
        val newWidth = maxWidth
        val newHeight = (height * ratio).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    /**
     * 解析OCR结果
     */
    private fun parseOCRResult(jsonStr: String): String? {
        return try {
            val json = JSONObject(jsonStr)
            val wordsResult = json.optJSONArray("words_result")

            if (wordsResult == null) {
                val errorMsg = json.optString("error_msg", "未知错误")
                Log.e(TAG, "OCR错误: $errorMsg")
                return null
            }

            val result = StringBuilder()
            for (i in 0 until wordsResult.length()) {
                val item = wordsResult.getJSONObject(i)
                val word = item.getString("words")
                result.append(word).append("\n")
            }

            val text = result.toString().trim()
            Log.d(TAG, "OCR识别结果:\n$text")
            text
        } catch (e: Exception) {
            Log.e(TAG, "解析OCR结果异常", e)
            null
        }
    }
}
