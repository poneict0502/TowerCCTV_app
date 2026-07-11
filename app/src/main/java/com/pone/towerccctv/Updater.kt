package com.pone.towerccctv

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * GitHub 릴리스 기반 앱 자동 업데이트(OTA).
 *
 * 사용 시나리오(현장): 태블릿을 인터넷(기사님 휴대폰 핫스팟 등)에 연결 →
 *   설정 → "앱 업데이트 확인" → 새 버전 있으면 다운로드·설치 → 다시 카메라 AP로 복귀.
 *
 * 버전 판별: 릴리스 태그(tag_name)에 versionCode(정수)를 넣고, 설치본 versionCode 와 비교.
 *   versionCode 는 build.gradle 에서 빌드 분(epoch)으로 자동 증가하므로 항상 단조 증가.
 *
 * TowerCCTV_app 저장소는 공개(public) → 토큰 없이 다운로드 가능.
 */
object Updater {
    // ── 릴리스 위치(공개 저장소) ──
    private const val OWNER = "poneict0502"
    private const val REPO  = "TowerCCTV_app"
    private const val ASSET = "tower.apk"

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    private data class Info(val code: Int, val title: String, val notes: String, val url: String)

    /** 설정 메뉴에서 호출: 확인 → 안내 → 다운로드 → 설치 */
    fun checkAndPrompt(act: Activity) {
        val scope = CoroutineScope(Dispatchers.Main)
        val busy = busyDialog(act, "업데이트 확인 중…\n(인터넷/핫스팟 연결 필요)")
        busy.show()
        scope.launch {
            val info = try {
                withContext(Dispatchers.IO) { fetchLatest() }
            } catch (e: Exception) {
                busy.dismiss()
                msg(act, "업데이트 확인 실패",
                    "인터넷에 연결돼 있는지 확인하세요.\n" +
                    "(기사님 휴대폰 핫스팟에 태블릿 Wi‑Fi를 연결한 뒤 다시 시도)\n\n오류: ${e.message}")
                return@launch
            }
            busy.dismiss()
            val cur = BuildConfig.VERSION_CODE
            if (info == null) { msg(act, "업데이트", "릴리스를 찾을 수 없습니다."); return@launch }
            if (info.code <= cur) {
                msg(act, "최신 상태", "이미 최신 버전입니다.\n\n현재: v${BuildConfig.VERSION_NAME} (code $cur)")
                return@launch
            }
            AlertDialog.Builder(act)
                .setTitle("🔄 새 버전 있음")
                .setMessage(
                    "새 버전: ${info.title}\n현재: v${BuildConfig.VERSION_NAME}\n\n" +
                    (info.notes.ifBlank { "새 버전으로 업데이트합니다." }) +
                    "\n\n지금 업데이트할까요? (다운로드 후 설치 확인창이 뜹니다)")
                .setPositiveButton("업데이트") { _, _ -> ensureInstallPermThenDownload(act, info) }
                .setNegativeButton("나중에", null)
                .show()
        }
    }

    private fun ensureInstallPermThenDownload(act: Activity, info: Info) {
        // Android 8+: '출처를 알 수 없는 앱 설치' 허용 필요(프로비저닝에서 미리 허용 권장)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("설치 권한 필요(1회)")
                .setMessage("업데이트를 설치하려면 이 앱에 '알 수 없는 앱 설치' 권한을 한 번 허용해야 합니다.\n\n" +
                    "[설정 열기] → 이 앱 허용 → 뒤로 → 다시 [앱 업데이트 확인]")
                .setPositiveButton("설정 열기") { _, _ ->
                    try {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + act.packageName)))
                    } catch (_: Exception) {
                        act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                    }
                }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        download(act, info)
    }

    private fun download(act: Activity, info: Info) {
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; isIndeterminate = false
        }
        val label = TextView(act).apply { text = "다운로드 중… 0%"; setPadding(48, 32, 48, 8) }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(bar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(48, 8, 48, 32) })
        }
        val dlg = AlertDialog.Builder(act)
            .setTitle("앱 업데이트 다운로드").setView(box).setCancelable(false).create()
        dlg.show()
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    val dir = File(act.getExternalFilesDir(null), "update").apply { mkdirs() }
                    val out = File(dir, ASSET)
                    downloadTo(info.url, out) { pct ->
                        scope.launch { bar.progress = pct; label.text = "다운로드 중… $pct%" }
                    }
                    out
                }
            } catch (e: Exception) {
                dlg.dismiss(); msg(act, "다운로드 실패", "오류: ${e.message}"); return@launch
            }
            dlg.dismiss()
            install(act, file)
        }
    }

    private fun install(act: Activity, file: File) {
        try {
            val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", file)
            val i = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            act.startActivity(i)
        } catch (e: Exception) {
            msg(act, "설치 실행 실패", "오류: ${e.message}")
        }
    }

    // ── 네트워크 ──
    private fun fetchLatest(): Info? {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "AIVION-Updater")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val root = JSONObject(resp.body!!.string())
            val code = root.optString("tag_name").filter { it.isDigit() }.toIntOrNull() ?: return null
            val title = root.optString("name").ifBlank { root.optString("tag_name") }
            val notes = root.optString("body")
            val assets = root.optJSONArray("assets") ?: return null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").equals(ASSET, true)) { url = a.optString("browser_download_url"); break }
            }
            if (url == null) {  // 자산명이 다르면 첫 .apk 사용(관대)
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk", true)) { url = a.optString("browser_download_url"); break }
                }
            }
            val finalUrl = url ?: return null
            return Info(code, title, notes, finalUrl)
        }
    }

    private fun downloadTo(url: String, out: File, onProgress: (Int) -> Unit) {
        val req = Request.Builder().url(url).header("User-Agent", "AIVION-Updater").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val body = resp.body ?: throw RuntimeException("empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int; var sum = 0L; var lastPct = -1
                    while (input.read(buf).also { read = it } >= 0) {
                        output.write(buf, 0, read); sum += read
                        if (total > 0) {
                            val pct = ((sum * 100) / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        }
    }

    // ── UI helpers ──
    private fun busyDialog(act: Activity, text: String): AlertDialog {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(56, 48, 56, 48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(ProgressBar(act))
            addView(TextView(act).apply { this.text = text; setPadding(40, 0, 0, 0) })
        }
        return AlertDialog.Builder(act).setView(box).setCancelable(false).create()
    }

    private fun msg(act: Activity, title: String, body: String) {
        AlertDialog.Builder(act).setTitle(title).setMessage(body).setPositiveButton("확인", null).show()
    }
}
