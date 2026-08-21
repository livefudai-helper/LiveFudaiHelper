package com.example.livefudai

import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import timber.log.Timber

class OCRManager {
    private val client = OkHttpClient()
    private val apiKey = "LfAuoZ7ESwrdb4uYHELPEskB"
    private val secretKey = "zM48dwXfX01RE6idOOASnCswD0KoSX15"

    fun recognizeText(imageBase64: String, callback: (String?) -> Unit) {
        // 先获取 access_token
        getAccessToken { token ->
            if (token == null) {
                Timber.e("获取 access_token 失败")
                callback(null)
                return@getAccessToken
            }

            val request = Request.Builder()
                .url("https://aip.baidu.com/rest/2.0/ocr/v1/general_basic?access_token=$token")
                .post(FormBody.Builder()
                    .add("image", imageBase64)
                    .build())
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Timber.e(e, "OCR 识别失败")
                    callback(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.body?.string()
                    Timber.d("OCR 结果: $result")
                    callback(result)
                }
            })
        }
    }

    private fun getAccessToken(callback: (String?) -> Unit) {
        val url = "https://aip.baidu.com/oauth/2.0/token?grant_type=client_credentials&client_id=$apiKey&client_secret=$secretKey"
        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Timber.e(e, "获取 token 失败")
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val token = json.optString("access_token", null)
                Timber.d("获取到 token: $token")
                callback(token)
            }
        })
    }
}
