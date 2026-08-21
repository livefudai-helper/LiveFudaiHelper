package com.example.livefudai

import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import timber.log.Timber

class UpdateManager {
    private val client = OkHttpClient()
    private val githubUser = "mayn"
    private val githubRepo = "livefudai-helper"

    fun checkUpdate(currentVersion: Int, callback: (String?) -> Unit) {
        val url = "https://api.github.com/repos/$githubUser/$githubRepo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Timber.e(e, "检查更新失败")
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val json = JSONObject(response.body?.string() ?: "{}")
                val tagStr = json.optString("tag_name", "0")
                val latestVersion = tagStr.replace("v", "").replace(".", "").trim().let {
                    if (it.isEmpty()) 0 else it.toIntOrNull() ?: 0
                }
                
                if (latestVersion > currentVersion) {
                    val apkUrl = json.getJSONArray("assets")
                        .getJSONObject(0)
                        .getString("browser_download_url")
                    Timber.d("发现新版本: $latestVersion")
                    callback(apkUrl)
                } else {
                    callback(null)
                }
            }
        })
    }
}
