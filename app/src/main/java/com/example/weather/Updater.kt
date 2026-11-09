package com.example.weather

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

data class UpdateInfo(val version: String, val url: String)

/** Self-update from GitHub Releases: finds the newest release, downloads its .apk and hands it to the system installer. */
object Updater {
    private const val REPO = "Vikmat01/PolarisWeather"

    fun installedVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun parts(v: String) = v.trim().removePrefix("v").split('.', '-').map { it.toIntOrNull() ?: 0 }

    fun isNewer(remote: String, local: String): Boolean {
        val a = parts(remote); val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Returns the update if a newer release with an .apk exists, null if up to date; throws if GitHub is unreachable. */
    suspend fun check(ctx: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val j = JSONObject(httpGetAny("update", listOf("https://api.github.com/repos/$REPO/releases/latest")))
        val tag = j.optString("tag_name").removePrefix("v")
        val assets = j.optJSONArray("assets") ?: return@withContext null
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk")) { url = a.getString("browser_download_url"); break }
        }
        if (url != null && tag.isNotEmpty() && isNewer(tag, installedVersion(ctx))) UpdateInfo(tag, url) else null
    }

    /** Downloads the APK (system notification shows progress) and opens the installer when it is done. */
    fun downloadAndInstall(ctx: Context, info: UpdateInfo) {
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val file = File(app.getExternalFilesDir(null), "update.apk").also { it.delete() }
        val dm = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(
            DownloadManager.Request(Uri.parse(info.url))
                .setTitle("Polaris Weather ${info.version}")
                .setDestinationUri(Uri.fromFile(file))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        )
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return
                app.unregisterReceiver(this)
                if (!file.exists() || file.length() == 0L) return
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                app.startActivity(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        ContextCompat.registerReceiver(app, rx, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
    }
}
