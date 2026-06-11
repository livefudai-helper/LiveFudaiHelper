package com.example.livefudai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

/**
 * OCR管理器 - 使用百度OCR REST API实现文字识别
 * 无需本地SDK jar包，直接通过HTTP调用百度API
 */
class OCRManager(private val context: Context) {

    companion object {
        private const val TAG = "OCRManager"
        private const val API_KEY = "LfAuoZ7ESwrdb4uYHELPEskB"
        private const val SECRET_KEY = "zM48dwXfX01RE6idOOASnCswD0KoSX15"
        private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        private const val OCR_URL = "https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic"
    }

    private val client = OkHttpClient()
    private val gson = Gson()
    private var accessToken: String? = null

    data class TokenResponse(
        @SerializedName("access_token") val accessToken: String?,
        @SerializedName("expires_in") val expiresIn: Long?,
        @SerializedName("error") val error: String?,
        @SerializedName("error_description") val errorDescription: String?
    )

    data class OcrResponse(
        @SerializedName("log_id") val logId: Long?,
        @SerializedName("words_result_num") val wordsResultNum: Int?,
        @SerializedName("words_result") val wordsResult: List<WordResult>?,
        @SerializedName("error_code") val errorCode: String?,
        @SerializedName("error_msg") val errorMsg: String?
    )

    data class WordResult(
        @SerializedName("words") val words: String?
    )

    /**
     * 获取百度OCR access_token
     */
    private suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        if (accessToken != null) return@withContext accessToken

        try {
            val url = "$TOKEN_URL?grant_type=client_credentials&client_id=$API_KEY&client_secret=$SECRET_KEY"
            val request = Request.Builder().url(url).post(FormBody.Builder().build()).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string()

            val tokenResponse = gson.fromJson(body, TokenResponse::class.java)
            if (tokenResponse?.accessToken != null) {
                accessToken = tokenResponse.accessToken
                Log.d(TAG, "AccessToken获取成功")
                return@withContext accessToken
            } else {
                Log.e(TAG, "获取Token失败: ${tokenResponse?.errorDescription}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取Token异常", e)
        }
        return@withContext null
    }

    /**
     * 识别Bitmap中的文字
     */
    suspend fun recognizeText(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val token = getAccessToken() ?: return@withContext ""

        try {
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val imageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)

            val url = "$OCR_URL?access_token=$token"
            val formBody = FormBody.Builder()
                .add("image", imageBase64)
                .add("language_type", "CHN_ENG")
                .build()

            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .addHeader("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string()

            val ocrResponse = gson.fromJson(body, OcrResponse::class.java)
            val text = ocrResponse?.wordsResult?.joinToString("\n") { it.words ?: "" } ?: ""
            Log.d(TAG, "OCR识别结果: $text")
            return@withContext text

        } catch (e: Exception) {
            Log.e(TAG, "OCR识别失败", e)
            return@withContext ""
        }
    }

    /**
     * 从文本中提取倒计时（秒）
     */
    fun extractCountdownFromText(text: String): Long {
        // 匹配 "03:25" 格式
        val timePattern1 = Regex("""(\d{1,2}):(\d{2})""")
        timePattern1.find(text)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0
            val seconds = match.groupValues[2].toLongOrNull() ?: 0
            return minutes * 60 + seconds
        }

        // 匹配 "3分25秒" 格式
        val timePattern2 = Regex("""(\d+)分(\d+)秒""")
        timePattern2.find(text)?.let { match ->
            val minutes = match.groupValues[1].toLongOrNull() ?: 0
            val seconds = match.groupValues[2].toLongOrNull() ?: 0
            return minutes * 60 + seconds
        }

        // 匹配 "剩余 180 秒" 格式
        val timePattern3 = Regex("""剩余\s*(\d+)\s*秒""")
        timePattern3.find(text)?.let { match ->
            return match.groupValues[1].toLongOrNull() ?: 0
        }

        return 0L
    }

    /**
     * 释放资源
     */
    fun release() {
        // OkHttp client 无需特殊释放
    }
}

/**
 * 福袋信息数据类
 */
data class FudaiInfo(
    val text: String,
    val countdownSeconds: Long,
    val bounds: Rect?
)
