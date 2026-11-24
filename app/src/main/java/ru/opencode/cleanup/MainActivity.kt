package ru.opencode.cleanup

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val TAG = "CleanupUI"
        private const val OC_PACKAGE = "com.openchamber.app"
        private const val CODE_PACKAGE = "app.getopencode"
        private const val SELF_API = "https://api.github.com/repos/vladimirgorodok/cleanup-ui-android/releases/latest"
        private const val SELF_APK = "https://github.com/vladimirgorodok/cleanup-ui-android/releases/latest/download/cleanup-ui.apk"
        private const val OC_LATEST_API = "https://api.github.com/repos/openchamber/openchamber/releases/latest"
        private const val CODE_LATEST_API = "https://api.github.com/repos/alvarolorentedev/opencode-mobile/releases/latest"
        private const val AI_LATEST_API = "https://api.github.com/repos/anomalyco/opencode/releases/latest"
        private const val AI_RELEASE_PAGE = "https://github.com/anomalyco/opencode/releases/latest"
        private const val OC_APK = "https://github.com/openchamber/openchamber/releases/download/canary/OpenChamber-canary-android.apk"
        private const val CODE_APK = "https://github.com/alvarolorentedev/opencode-mobile/releases/latest/download/opencode-mobile.apk"
        private const val FALLBACK_SELF_VERSION = "1.2"
        private const val FALLBACK_OC_VERSION = "2.8.0"
        private const val FALLBACK_CODE_VERSION = "1.0.172"
        private const val FALLBACK_AI_VERSION = "1.19.6"
        private const val REQ_PERMISSIONS = 42
    }

    private data class Job(
        val id: String,
        val title: String,
        val description: String,
        val risk: String,
        val paths: List<String>,
    )

    private lateinit var jobs: List<Job>

    private fun buildJobs() = listOf(
        Job(
            "oc-caches", "Кэши OpenChamber",
            "Удаляет каталоги cache/logs внутри рабочих каталогов OpenChamber. Приложение создаст их заново.",
            "low", ocRoots(),
        ),
        Job(
            "code-caches", "Кэши OpenCode Mobile",
            "Чистит кэши и логи в каталогах OpenCode Mobile. Данные сессий не трогаются.",
            "low", codeRoots(),
        ),
        Job(
            "temp-files", "Временные файлы и логи",
            "Удаляет *.tmp, *.temp и *.log старше 7 дней в найденных рабочих каталогах.",
            "low", workRoots(),
        ),
        Job(
            "thumbnails", "Миниатюры",
            "Удаляет скрытые каталоги .thumbnails в DCIM и Pictures. Фото остаются на месте.",
            "low", listOf("DCIM/.thumbnails", "Pictures/.thumbnails").map { sdcard(it) },
        ),
        Job(
            "own-cache", "Кэш этого приложения",
            "Очищает собственный WebView/кэш приложения «Очистка».",
            "low", listOf(cacheDir.absolutePath, codeCacheDir.absolutePath),
        ),
        Job(
            "old-apks", "Старые APK в Загрузках",
            "Оставляет самую свежую версию каждого APK в Загрузках, остальные удаляет. Внимательно проверьте список.",
            "medium", listOf(sdcard("Download"), sdcard("Download/OpenChamber")),
        ),
        Job(
            "empty-dirs", "Пустые каталоги",
            "Рекурсивно удаляет пустые каталоги в найденных рабочих каталогах.",
            "medium", workRoots(),
        ),
    )

    private lateinit var webView: WebView
    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingDownloadId = -1L
    private var downloadReceiverRegistered = false

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
            if (id == pendingDownloadId) installDownloaded(id)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        jobs = buildJobs()

        webView = WebView(this)
        setContentView(webView)
        webView.setBackgroundColor(android.graphics.Color.rgb(14, 17, 22))

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            userAgentString = "$userAgentString CleanupUI/1.0"
        }
        webView.addJavascriptInterface(this, "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                if (url.startsWith("file:///android_asset/") || url.startsWith("javascript:")) return false
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Exception) {
                    toast("Не удалось открыть ссылку")
                }
                return true
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        downloadReceiverRegistered = true

        requestStorageAccess()
        webView.loadUrl("file:///android_asset/index.html")
    }

    override fun onDestroy() {
        if (downloadReceiverRegistered) {
            try { unregisterReceiver(downloadReceiver) } catch (_: Exception) {}
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
                toast("Разрешите «Доступ ко всем файлам» для очистки")
            }
        } else if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_PERMISSIONS,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMISSIONS + 1)
        }
    }

    private fun toast(text: String) {
        mainHandler.post { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    private fun callJs(name: String, payload: JSONObject) {
        val quoted = JSONObject.quote(payload.toString())
        mainHandler.post { webView.evaluateJavascript("window.$name($quoted)", null) }
    }

    private fun sdcard(relative: String): String =
        File(Environment.getExternalStorageDirectory(), relative).absolutePath

    private fun ocRoots() = listOf(
        sdcard("Android/data/$OC_PACKAGE/files"),
        sdcard("Android/data/$OC_PACKAGE/cache"),
        sdcard("OpenChamber"),
        sdcard("Download/OpenChamber"),
        sdcard("Documents/OpenChamber"),
    )

    private fun codeRoots() = listOf(
        sdcard("Android/data/$CODE_PACKAGE/files"),
        sdcard("Android/data/$CODE_PACKAGE/cache"),
        sdcard("OpenCode"),
        sdcard("Download/OpenCode"),
    )

    private fun workRoots(): List<String> =
        (ocRoots() + codeRoots() + listOf(sdcard("Download"), sdcard("Documents"))).distinct()

    private fun dirInfo(path: String): JSONObject {
        val file = File(path)
        var size = 0L
        var files = 0
        var accessible = false
        try {
            accessible = file.exists() && file.canRead()
            if (accessible) {
                val stack = ArrayDeque<File>()
                stack.add(file)
                var visited = 0
                while (stack.isNotEmpty() && visited < 200_000) {
                    val current = stack.removeLast()
                    visited++
                    val children = current.listFiles() ?: continue
                    for (child in children) {
                        if (child.isDirectory) stack.add(child)
                        else {
                            files++
                            size += try { child.length() } catch (_: Exception) { 0L }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return JSONObject()
            .put("path", path)
            .put("exists", file.exists())
            .put("accessible", accessible)
            .put("sizeMb", size / (1024 * 1024))
            .put("files", files)
    }

    private fun sumPaths(paths: List<String>): Long =
        paths.sumOf { dirInfo(it).optLong("sizeMb", 0) }

    @JavascriptInterface
    fun getInstalledApps(): String {
        val array = JSONArray()
        for (pkg in listOf(packageName, OC_PACKAGE, CODE_PACKAGE)) {
            val info = try {
                packageManager.getPackageInfo(pkg, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val app = JSONObject()
                .put("package", pkg)
                .put("installed", info != null)
                .put(
                    "name",
                    when (pkg) {
                        OC_PACKAGE -> "OpenChamber"
                        CODE_PACKAGE -> "OpenCode Mobile"
                        else -> "Очистка UI"
                    }
                )
            if (info != null) {
                app.put("version", info.versionName ?: "—")
                app.put("versionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong())
                app.put("label", try { info.applicationInfo?.loadLabel(packageManager)?.toString() } catch (_: Exception) { pkg })
            }
            array.put(app)
        }
        return array.toString()
    }

    @JavascriptInterface
    fun getWorkDirs(): String {
        val array = JSONArray()
        for (path in workRoots()) array.put(dirInfo(path))
        return array.toString()
    }

    @JavascriptInterface
    fun getAnalysis(): String {
        val root = JSONObject()
        val dirs = JSONArray()
        for (path in workRoots()) dirs.put(dirInfo(path))
        root.put("dirs", dirs)
        root.put("ownCacheMb", sumPaths(listOf(cacheDir.absolutePath, codeCacheDir.absolutePath)))
        root.put("downloadMb", sumPaths(listOf(sdcard("Download"))))
        return root.toString()
    }

    @JavascriptInterface
    fun getJobs(): String {
        val array = JSONArray()
        for (job in jobs) {
            array.put(
                JSONObject()
                    .put("id", job.id)
                    .put("title", job.title)
                    .put("description", job.description)
                    .put("risk", job.risk)
                    .put("estimatedMb", sumPaths(job.paths))
            )
        }
        return array.toString()
    }

    @JavascriptInterface
    fun checkUpdatesAsync() {
        executor.execute {
            var selfLatest = FALLBACK_SELF_VERSION
            var ocLatest = FALLBACK_OC_VERSION
            var codeLatest = FALLBACK_CODE_VERSION
            var aiLatest = FALLBACK_AI_VERSION
            var error: String? = null
            try { selfLatest = fetchLatestVersion(SELF_API).trimStart('v') } catch (e: Exception) { error = "Очистка UI: ${e.message}" }
            try { ocLatest = fetchLatestVersion(OC_LATEST_API).trimStart('v') } catch (e: Exception) { error = (error ?: "") + " OpenChamber: ${e.message}" }
            try { codeLatest = fetchLatestVersion(CODE_LATEST_API).trimStart('v') } catch (e: Exception) { error = (error ?: "") + " OpenCode: ${e.message}" }
            try { aiLatest = fetchLatestVersion(AI_LATEST_API).trimStart('v') } catch (e: Exception) { error = (error ?: "") + " OpenCode AI: ${e.message}" }

            val apps = JSONArray(getInstalledApps())
            for (i in 0 until apps.length()) {
                val app = apps.getJSONObject(i)
                val installed = app.optString("version", "")
                val pkg = app.getString("package")
                val latest = when (pkg) {
                    packageName -> selfLatest
                    OC_PACKAGE -> ocLatest
                    else -> codeLatest
                }
                app.put("latest", latest)
                app.put("outdated", installed.isNotBlank() && compareVersions(installed, latest) < 0)
                app.put(
                    "updateUrl",
                    when (pkg) {
                        packageName -> SELF_APK
                        OC_PACKAGE -> OC_APK
                        else -> CODE_APK
                    }
                )
            }
            val payload = JSONObject()
                .put("apps", apps)
                .put("selfLatest", selfLatest)
                .put("ocLatest", ocLatest)
                .put("codeLatest", codeLatest)
                .put("aiLatest", aiLatest)
                .put("aiUrl", AI_RELEASE_PAGE)
            if (error != null) payload.put("error", error)
            callJs("onUpdates", payload)
        }
    }

    private fun fetchLatestVersion(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "CleanupUI/1.0")
        return connection.inputStream.use { stream ->
            val text = stream.bufferedReader().use { it.readText() }
            JSONObject(text).optString("tag_name", "").ifBlank { throw Exception("нет tag_name") }
        }
    }

    private fun numericParts(value: String): List<Int> =
        Regex("\\d+").findAll(value).map { it.value.toInt() }.take(3).toList()

    private fun compareVersions(a: String, b: String): Int {
        val left = numericParts(a)
        val right = numericParts(b)
        for (i in 0 until 3) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    @JavascriptInterface
    fun openApp(pkg: String) {
        try {
            startActivity(packageManager.getLaunchIntentForPackage(pkg))
        } catch (_: Exception) {
            toast("Приложение не установлено")
        }
    }

    @JavascriptInterface
    fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("Не удалось открыть ссылку")
        }
    }

    @JavascriptInterface
    fun updateApp(pkg: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
            toast("Разрешите установку APK для этого приложения")
            return
        }
        val url = when (pkg) {
            packageName -> SELF_APK
            OC_PACKAGE -> OC_APK
            else -> CODE_APK
        }
        val name = when (pkg) {
            packageName -> "cleanup-ui.apk"
            OC_PACKAGE -> "OpenChamber-canary.apk"
            else -> "opencode-mobile.apk"
        }
        val title = when (pkg) {
            packageName -> "Очистка UI"
            OC_PACKAGE -> "OpenChamber"
            else -> "OpenCode Mobile"
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Обновление $title")
            .setDescription("Загрузка APK с GitHub")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(true)
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        pendingDownloadId = manager.enqueue(request)
        toast("APK скачивается в Загрузки")
        callJs("onUpdateStarted", JSONObject().put("package", pkg).put("downloadId", pendingDownloadId))
    }

    private fun installDownloaded(id: Long) {
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val query = DownloadManager.Query().setFilterById(id)
        val cursor = manager.query(query)
        cursor.use {
            if (!it.moveToFirst()) return
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                toast("Не удалось скачать APK")
                return
            }
        }
        val source = manager.getUriForDownloadedFile(id)
        if (source == null) {
            toast("Не найден скачанный APK")
            return
        }
        try {
            val targetDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").apply { mkdirs() }
            val target = File(targetDir, "update.apk")
            contentResolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", target)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            toast("Не удалось открыть APK: ${e.message}")
        }
    }

    private fun deleteRecursively(file: File): Long {
        var freed = 0L
        try {
            if (file.isFile) {
                freed = file.length()
                file.delete()
            } else if (file.isDirectory) {
                file.listFiles()?.forEach { freed += deleteRecursively(it) }
                file.delete()
            }
        } catch (_: Exception) {}
        return freed
    }

    private fun runJob(id: String): Long {
        var freed = 0L
        try {
            when (id) {
                "oc-caches", "code-caches" -> {
                    val roots = if (id == "oc-caches") ocRoots() else codeRoots()
                    for (root in roots) {
                        val dir = File(root)
                        if (!dir.exists() || !dir.canRead()) continue
                        dir.walkTopDown().toList().forEach { child ->
                            if (child.isDirectory && child.name.lowercase() in setOf("cache", "code cache", "logs", "log")) {
                                freed += deleteRecursively(child)
                            }
                        }
                    }
                }
                "temp-files" -> {
                    val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
                    for (root in workRoots()) {
                        val dir = File(root)
                        if (!dir.exists() || !dir.canRead()) continue
                        dir.walkTopDown().toList().forEach { child ->
                            if (child.isFile && child.lastModified() < cutoff) {
                                val lower = child.name.lowercase()
                                if (lower.endsWith(".tmp") || lower.endsWith(".temp") || lower.endsWith(".log")) {
                                    freed += deleteRecursively(child)
                                }
                            }
                        }
                    }
                }
                "thumbnails" -> {
                    for (path in jobs.first { it.id == "thumbnails" }.paths) {
                        freed += deleteRecursively(File(path))
                    }
                }
                "own-cache" -> {
                    freed += deleteRecursively(cacheDir)
                    freed += deleteRecursively(codeCacheDir)
                    cacheDir.mkdirs()
                    codeCacheDir.mkdirs()
                }
                "old-apks" -> {
                    for (root in jobs.first { it.id == "old-apks" }.paths) {
                        val dir = File(root)
                        if (!dir.exists() || !dir.canRead()) continue
                        val groups = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", true) }
                            ?.groupBy { it.name.replace(Regex("-v?\\d[\\d.]*"), "") }
                            ?: emptyMap()
                        for ((_, files) in groups) {
                            val sorted = files.sortedByDescending { it.lastModified() }
                            sorted.drop(1).forEach { freed += deleteRecursively(it) }
                        }
                    }
                }
                "empty-dirs" -> {
                    for (root in workRoots()) {
                        val dir = File(root)
                        if (!dir.exists() || !dir.canRead()) continue
                        dir.walkTopDown().toList().sortedByDescending { it.absolutePath.length }
                            .forEach { child ->
                                if (child.isDirectory && (child.listFiles()?.isEmpty() == true)) {
                                    if (child.delete()) freed += 1
                                }
                            }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "job $id failed", e)
        }
        return freed
    }

    @JavascriptInterface
    fun runCleanupAsync(idsJson: String) {
        val ids = try {
            val array = JSONArray(idsJson)
            (0 until array.length()).map { array.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
        executor.execute {
            val total = ids.size
            var done = 0
            for (id in ids) {
                val job = jobs.firstOrNull { it.id == id } ?: continue
                callJs(
                    "onProgress", JSONObject()
                        .put("type", "start")
                        .put("id", id)
                        .put("title", job.title)
                        .put("done", done)
                        .put("total", total)
                )
                val freedMb = runJob(id) / (1024 * 1024)
                done++
                callJs(
                    "onProgress", JSONObject()
                        .put("type", "done")
                        .put("id", id)
                        .put("title", job.title)
                        .put("freedMb", freedMb)
                        .put("done", done)
                        .put("total", total)
                )
            }
            callJs("onDone", JSONObject().put("ok", true).put("count", done))
        }
    }
}
