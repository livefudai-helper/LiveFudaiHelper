package com.example.livefudai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 自动更新管理器
 * 负责检查GitHub Release、下载APK、安装APK
 */
class UpdateManager(private val context: Context) {
    
    companion object {
        private const val TAG = "UpdateManager"
        // GitHub API URL（会从strings.xml中读取）
        // private const val GITHUB_USER = "你的用户名"
        // private const val GITHUB_REPO = "你的仓库名"
        // private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases/latest"
        
        // 当前版本号（会从strings.xml中读取）
        // private const val CURRENT_VERSION = "v1.0.7"
    }
    
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * 检查更新
     * @param callback 回调：(是否有更新, 最新Release信息, 错误信息)
     */
    fun checkUpdate(callback: (Boolean, GitHubRelease?, String?) -> Unit) {
        scope.launch {
            try {
                // 从strings.xml读取配置
                val githubUser = context.getString(R.string.github_user)
                val githubRepo = context.getString(R.string.github_repo)
                val currentVersion = context.getString(R.string.current_version)
                
                // 构建GitHub API URL
                val apiUrl = "https://api.github.com/repos/$githubUser/$githubRepo/releases/latest"
                
                // 调用GitHub API
                val request = Request.Builder()
                    .url(apiUrl)
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        callback(false, null, "HTTP ${response.code}")
                    }
                    return@launch
                }
                
                val json = JSONObject(response.body?.string() ?: "")
                val latestVersion = json.getString("tag_name")
                val release = GitHubRelease.fromJSON(json)
                
                // 比较版本号
                val hasUpdate = compareVersion(latestVersion, currentVersion)
                
                withContext(Dispatchers.Main) {
                    callback(hasUpdate, release, null)
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(false, null, e.message)
                }
            }
        }
    }
    
    /**
     * 下载并安装APK
     * @param release Release信息
     * @param callback 回调：(进度-1表示完成, 错误信息)
     */
    fun downloadAndInstall(release: GitHubRelease, callback: (Int, String?) -> Unit) {
        scope.launch {
            try {
                // 获取APK下载URL
                val apkAsset = release.assets.firstOrNull()
                if (apkAsset == null) {
                    withContext(Dispatchers.Main) {
                        callback(-1, "未找到APK文件")
                    }
                    return@launch
                }
                
                val apkUrl = apkAsset.browserDownloadUrl
                val fileName = "update.apk"
                val apkFile = File(context.getExternalFilesDir(null), fileName)
                
                // 下载APK
                val request = Request.Builder()
                    .url(apkUrl)
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        callback(-1, "下载失败: HTTP ${response.code}")
                    }
                    return@launch
                }
                
                val body = response.body ?: run {
                    withContext(Dispatchers.Main) {
                        callback(-1, "响应体为空")
                    }
                    return@launch
                }
                
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                
                // 保存文件
                FileOutputStream(apkFile).use { output ->
                    val input = body.byteStream()
                    val buffer = ByteArray(4096)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        // 计算进度
                        if (totalBytes > 0) {
                            val progress = (downloadedBytes * 100 / totalBytes).toInt()
                            withContext(Dispatchers.Main) {
                                callback(progress, null)
                            }
                        }
                    }
                }
                
                // 下载完成，开始安装
                withContext(Dispatchers.Main) {
                    callback(-1, null) // -1 表示下载完成
                    installAPK(apkFile)
                }
                
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    callback(-1, e.message)
                }
            }
        }
    }
    
    /**
     * 安装APK
     */
    private fun installAPK(apkFile: File) {
        try {
            // Android 8.0+ 需要授权
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    // 跳转到设置页面
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
            }
            
            // 安装APK
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Android 7.0+ 使用FileProvider
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        apkFile
                    )
                } else {
                    Uri.fromFile(apkFile)
                }
                
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(intent)
            
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(context, "安装失败: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 比较版本号
     * @return true if latest > current
     */
    private fun compareVersion(latest: String, current: String): Boolean {
        // 简单比较：去掉"v"前缀，按数字比较
        val latestNums = latest.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val currentNums = current.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        
        for (i in 0 until maxOf(latestNums.size, currentNums.size)) {
            val l = latestNums.getOrNull(i) ?: 0
            val c = currentNums.getOrNull(i) ?: 0
            
            if (l > c) return true
            if (l < c) return false
        }
        
        return false // 版本相同
    }
    
    /**
     * 取消所有任务
     */
    fun cancel() {
        scope.cancel()
    }
}

/**
 * GitHub Release数据类
 */
data class GitHubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val publishedAt: String,
    val assets: List<GitHubAsset>,
    val htmlUrl: String
) {
    companion object {
        fun fromJSON(json: JSONObject): GitHubRelease {
            val assetsArray = json.getJSONArray("assets")
            val assets = mutableListOf<GitHubAsset>()
            
            for (i in 0 until assetsArray.length()) {
                val assetJson = assetsArray.getJSONObject(i)
                assets.add(GitHubAsset.fromJSON(assetJson))
            }
            
            return GitHubRelease(
                tagName = json.getString("tag_name"),
                name = json.optString("name", ""),
                body = json.optString("body", "无更新说明"),
                publishedAt = json.optString("published_at", ""),
                assets = assets,
                htmlUrl = json.optString("html_url", "")
            )
        }
    }
}

/**
 * GitHub Asset数据类
 */
data class GitHubAsset(
    val name: String,
    val size: Long,
    val browserDownloadUrl: String
) {
    companion object {
        fun fromJSON(json: JSONObject): GitHubAsset {
            return GitHubAsset(
                name = json.getString("name"),
                size = json.getLong("size"),
                browserDownloadUrl = json.getString("browser_download_url")
            )
        }
    }
}
