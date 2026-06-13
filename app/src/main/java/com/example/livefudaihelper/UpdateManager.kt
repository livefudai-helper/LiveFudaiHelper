package com.example.livefudaihelper

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object UpdateManager {

    private const val TAG = "UpdateManager"
    
    // ⚠️ 重要：请替换为你的 GitHub 用户名和仓库名
    private const val GITHUB_USER = "livefudai-helper"
    private const val GITHUB_REPO = "LiveFudaiHelper"
    private const val GITHUB_API_URL = "https://api.github.com/repos/$GITHUB_USER/$GITHUB_REPO/releases/latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var downloadReceiver: BroadcastReceiver? = null
    private var currentContext: Context? = null

    /**
     * 检查是否有新版本
     */
    fun checkForUpdate(
        context: Context,
        currentVersionCode: Int,
        onUpdateAvailable: (versionName: String, downloadUrl: String, releaseNotes: String) -> Unit,
        onNoUpdate: () -> Unit,
        onError: (String) -> Unit
    ) {
        currentContext = context
        
        // 检查是否配置了 GitHub 用户名
        if (GITHUB_USER == "YOUR_GITHUB_USERNAME") {
            onError("请先在 UpdateManager.kt 中配置你的 GitHub 用户名")
            return
        }

        Toast.makeText(context, "正在检查更新...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val request = Request.Builder()
                    .url(GITHUB_API_URL)
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string()

                if (body == null) {
                    Log.e(TAG, "GitHub API 响应为空")
                    withContext(Dispatchers.Main) { onError("GitHub API 响应为空") }
                    return@launch
                }

                val json = JSONObject(body)
                
                // 检查是否有错误信息
                if (json.has("message")) {
                    val errorMsg = json.getString("message")
                    Log.e(TAG, "GitHub API 错误: $errorMsg")
                    withContext(Dispatchers.Main) { onError("GitHub API 错误: $errorMsg") }
                    return@launch
                }

                val latestVersionName = json.getString("tag_name")
                val releaseNotes = json.optString("body", "无更新说明")
                val assets = json.getJSONArray("assets")

                if (assets.length() == 0) {
                    Log.w(TAG, "没有找到 APK 文件")
                    withContext(Dispatchers.Main) { onError("没有找到 APK 文件，请确保 Release 中包含了 app-debug.apk") }
                    return@launch
                }

                val downloadUrl = assets.getJSONObject(0).getString("browser_download_url")
                val versionCode = parseVersionCode(latestVersionName)

                Log.d(TAG, "当前版本: $currentVersionCode, 最新版本: $versionCode ($latestVersionName)")

                if (versionCode > currentVersionCode) {
                    Log.d(TAG, "发现新版本: $latestVersionName")
                    withContext(Dispatchers.Main) {
                        onUpdateAvailable(latestVersionName, downloadUrl, releaseNotes)
                    }
                } else {
                    withContext(Dispatchers.Main) { onNoUpdate() }
                }

            } catch (e: IOException) {
                Log.e(TAG, "网络错误", e)
                withContext(Dispatchers.Main) { onError("网络错误: ${e.message}") }
            } catch (e: Exception) {
                Log.e(TAG, "检查更新失败", e)
                withContext(Dispatchers.Main) { onError("检查更新失败: ${e.message}") }
            }
        }
    }

    /**
     * 显示更新对话框并开始下载
     */
    fun showUpdateDialogAndDownload(
        context: Context,
        versionName: String,
        downloadUrl: String,
        releaseNotes: String
    ) {
        android.app.AlertDialog.Builder(context)
            .setTitle("发现新版本 $versionName")
            .setMessage("更新说明:\n$releaseNotes\n\n是否现在更新？")
            .setPositiveButton("立即更新") { _, _ ->
                downloadAndInstall(context, downloadUrl, versionName)
            }
            .setNegativeButton("稍后再说") { _, _ -> }
            .setCancelable(false)
            .show()
    }

    /**
     * 下载并安装 APK
     */
    fun downloadAndInstall(context: Context, downloadUrl: String, versionName: String) {
        try {
            val uri = Uri.parse(downloadUrl)
            val request = DownloadManager.Request(uri)

            request.setTitle("福袋助手 $versionName")
            request.setDescription("正在下载更新...")
            
            // 保存到 APP 私有目录（不需要存储权限）
            request.setDestinationInExternalFilesDir(
                context,
                null,  // 根目录
                "app-debug.apk"
            )
            
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setMimeType("application/vnd.android.package-archive")
            request.allowScanningByMediaScanner()

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val downloadId = downloadManager.enqueue(request)

            Toast.makeText(context, "开始下载更新...", Toast.LENGTH_SHORT).show()

            // 注册广播接收器，监听下载完成
            unregisterReceiver(context)
            
            downloadReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                    if (id == downloadId) {
                        Log.d(TAG, "APK 下载完成")
                        Toast.makeText(ctx, "下载完成，正在安装...", Toast.LENGTH_SHORT).show()
                        
                        // 安装 APK
                        installApk(ctx ?: context, downloadId)
                        
                        // 注销接收器
                        try {
                            ctx?.unregisterReceiver(this)
                            downloadReceiver = null
                        } catch (e: Exception) {
                            Log.e(TAG, "注销接收器失败", e)
                        }
                    }
                }
            }

            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            context.registerReceiver(downloadReceiver, filter)

        } catch (e: Exception) {
            Log.e(TAG, "下载失败", e)
            Toast.makeText(context, "下载失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 安装 APK
     */
    private fun installApk(context: Context, downloadId: Long) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = downloadManager.getUriForDownloadedFile(downloadId)

            if (uri != null) {
                Log.d(TAG, "APK URI: $uri")
                
                val intent = Intent(Intent.ACTION_VIEW)
                intent.setDataAndType(uri, "application/vnd.android.package-archive")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                
                context.startActivity(intent)
            } else {
                Toast.makeText(context, "安装文件找不到，请手动安装", Toast.LENGTH_LONG).show()
                Log.e(TAG, "APK URI 为空")
            }
        } catch (e: Exception) {
            Log.e(TAG, "安装失败", e)
            Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 注销广播接收器
     */
    fun unregisterReceiver(context: Context) {
        if (downloadReceiver != null) {
            try {
                context.unregisterReceiver(downloadReceiver)
                downloadReceiver = null
                Log.d(TAG, "广播接收器已注销")
            } catch (e: Exception) {
                Log.e(TAG, "注销接收器失败", e)
            }
        }
    }

    /**
     * 解析版本号（如 v1.12 -> 12）
     */
    private fun parseVersionCode(versionName: String): Int {
        return try {
            val cleanName = versionName.replace("v", "").replace(".", "")
            cleanName.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "解析版本号失败: $versionName", e)
            0
        }
    }
}
