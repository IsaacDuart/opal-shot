package com.example.opalshot

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.provider.Settings
import android.graphics.PixelFormat
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors

class ScreenshotWatcherService : Service() {
    private data class Shot(val uri: Uri, val name: String, val path: String?)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var observer: ContentObserver
    private var overlayView: View? = null
    private var lastUri: String? = null
    private var lastProcessedAt = 0L
    private val cleanup = object : Runnable {
        override fun run() {
            if (!worker.isShutdown) worker.execute { cleanTemporaryFiles(this@ScreenshotWatcherService, MAX_AGE) }
            main.postDelayed(this, 60_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannels()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(MONITOR_ID, monitorNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(MONITOR_ID, monitorNotification())
        isRunning = true
        observer = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                main.postDelayed({
                    if (!worker.isShutdown) worker.execute { inspect(uri) }
                }, 350)
            }
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer
        )
        worker.execute { cleanTemporaryFiles(this, MAX_AGE) }
        main.postDelayed(cleanup, 60_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        runCatching { contentResolver.unregisterContentObserver(observer) }
        removeOverlay()
        main.removeCallbacksAndMessages(null)
        worker.shutdown()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun inspect(changed: Uri?) {
        val shot = queryShot(changed) ?: return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (lastUri == shot.first.uri.toString() && now - lastProcessedAt < 30_000) return
            lastUri = shot.first.uri.toString()
            lastProcessedAt = now
        }
        showShotCard(shot.first)
    }

    private fun queryShot(changed: Uri?): Pair<Shot, Long>? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val specific = changed?.takeIf { it != collection && it.lastPathSegment?.toLongOrNull() != null }
        val columns = arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
        val cursor = querySafely(specific ?: collection, columns,
            if (specific == null) "${MediaStore.Images.Media.DATE_ADDED} DESC" else null) ?: return null
        cursor.use {
            if (!it.moveToFirst()) return null
            val name = it.text(MediaStore.Images.Media.DISPLAY_NAME)
            val relative = it.text(MediaStore.Images.Media.RELATIVE_PATH)
            val path = it.text(MediaStore.Images.Media.DATA).ifBlank {
                relative.takeIf(String::isNotBlank)?.let {
                    File(Environment.getExternalStorageDirectory(), "$it$name").absolutePath
                }
            }
            val added = it.long(MediaStore.Images.Media.DATE_ADDED) * 1000L
            val taken = it.long(MediaStore.Images.Media.DATE_TAKEN)
            val created = maxOf(taken, added)
            val age = System.currentTimeMillis() - created
            if (age !in -2_000L..12_000L || !looksLikeScreenshot("$relative/$name/${path.orEmpty()}")) return null
            val uri = specific ?: ContentUris.withAppendedId(collection,
                it.long(MediaStore.Images.Media._ID))
            return Shot(uri, name.ifBlank { "screenshot.png" }, path) to created
        }
    }

    private fun querySafely(uri: Uri, columns: Array<String>, sort: String?): Cursor? {
        return try { contentResolver.query(uri, columns, null, null, sort) }
        catch (_: Exception) {
            runCatching { contentResolver.query(uri, columns.dropLast(1).toTypedArray(), null, null, sort) }.getOrNull()
        }
    }

    private fun Cursor.text(column: String) = getColumnIndex(column).takeIf { it >= 0 }
        ?.let { if (isNull(it)) "" else getString(it) }.orEmpty()
    private fun Cursor.long(column: String) = getColumnIndex(column).takeIf { it >= 0 }
        ?.let { if (isNull(it)) 0L else getLong(it) } ?: 0L

    private fun looksLikeScreenshot(value: String): Boolean {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{M}+".toRegex(), "").lowercase(Locale.ROOT)
        val compact = normalized.replace("[^a-z0-9]".toRegex(), "")
        return listOf("screenshot", "screen shot", "screen capture", "captura de tela",
            "captura de pantalla", "captura de ecra", "capture d ecran", "bildschirmfoto",
            "schermata", "ekran goruntusu", "截屏", "截图", "スクリーンショット", "스크린샷")
            .any { normalized.contains(it) || compact.contains(it.replace(" ", "")) }
    }

    private fun showShotCard(shot: Shot) = main.post {
        if (!Settings.canDrawOverlays(this)) {
            showError("Autorize o OpalShot a aparecer sobre outros apps")
            return@post
        }
        removeOverlay()
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_screenshot_card, null)
        view.findViewById<TextView>(R.id.overlayFileName).text = shot.name
        val temporary = view.findViewById<Button>(R.id.overlayTemporary)
        val keep = view.findViewById<Button>(R.id.overlayKeep)
        val delete = view.findViewById<Button>(R.id.overlayDelete)
        temporary.setOnClickListener {
            temporary.isEnabled = false
            keep.isEnabled = false
            delete.isEnabled = false
            temporary.setText(R.string.preparing_share)
            worker.execute { makeTemporary(shot.uri, shot.name, shot.path) }
        }
        keep.setOnClickListener { removeOverlay() }
        delete.setOnClickListener {
            removeOverlay()
            worker.execute {
                if (!deleteOriginal(shot.uri, shot.path)) showError("Não foi possível excluir o print")
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (56 * resources.displayMetrics.density).toInt()
            windowAnimations = android.R.style.Animation_Dialog
        }
        runCatching { getSystemService(WindowManager::class.java).addView(view, params) }
            .onSuccess { overlayView = view }
            .onFailure { showError("Não foi possível mostrar o card do print") }
    }

    private fun removeOverlay() {
        overlayView?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }
        overlayView = null
    }

    private fun makeTemporary(uri: Uri, rawName: String?, originalPath: String?) {
        try {
            val dir = File(cacheDir, "temp_shots").apply { mkdirs() }
            val safeName = rawName.orEmpty().replace("[^A-Za-z0-9._-]".toRegex(), "_")
                .ifBlank { "screenshot.png" }
            val target = File(dir, "${System.currentTimeMillis()}_$safeName")
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Imagem indisponível" }
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (!deleteOriginal(uri, originalPath)) showError("Cópia criada, mas o original não pôde ser excluído")
            openShare(target)
        } catch (_: Exception) {
            showError("Não foi possível preparar o print para compartilhar")
            main.post { removeOverlay() }
        }
    }

    private fun openShare(file: File) = main.post {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val mime = contentResolver.getType(uri) ?: "image/*"
            val send = Intent(Intent.ACTION_SEND).apply {
                setDataAndType(uri, mime)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "print", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, "Compartilhar print").apply {
                clipData = send.clipData
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(chooser)
        } catch (_: Exception) {
            showError("Não foi possível abrir o compartilhamento")
        } finally { removeOverlay() }
    }

    private fun deleteOriginal(uri: Uri, path: String?): Boolean {
        try { if (contentResolver.delete(uri, null, null) > 0) return true }
        catch (_: SecurityException) { }
        catch (_: Exception) { }
        if (path.isNullOrBlank()) return false
        val file = File(path)
        if (file.exists() && !runCatching { file.delete() }.getOrDefault(false)) return false
        runCatching { contentResolver.delete(uri, null, null) }
        MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
        return !file.exists()
    }

    private fun showError(message: String) {
        main.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(MONITOR_CHANNEL, "Monitoramento",
            NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    private fun monitorNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_opalshot).setContentTitle("OpalShot ativo")
            .setContentText("Monitorando novas capturas de tela").setContentIntent(open)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    companion object {
        private const val MONITOR_CHANNEL = "monitor"
        private const val MONITOR_ID = 1
        private const val MAX_AGE = 10 * 60 * 1000L
        @Volatile var isRunning = false
            private set

        fun cleanTemporaryFiles(context: Context, maxAge: Long): Int {
            val now = System.currentTimeMillis()
            return File(context.cacheDir, "temp_shots").listFiles()?.count {
                (maxAge == 0L || now - it.lastModified() > maxAge) && runCatching { it.delete() }.getOrDefault(false)
            } ?: 0
        }
    }
}
