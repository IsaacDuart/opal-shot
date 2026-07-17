package com.example.opalshot

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {
    private val requestCode = 7
    private var settingsStep = 0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_main)
        findViewById<android.view.View>(R.id.grantButton).setOnClickListener { requestNeededPermissions() }
        findViewById<android.view.View>(R.id.startButton).setOnClickListener {
            if (!hasAllPermissions()) {
                Toast.makeText(this, "Conceda todas as permissões primeiro", Toast.LENGTH_LONG).show()
                requestNeededPermissions()
            } else {
                ContextCompat.startForegroundService(this, Intent(this, ScreenshotWatcherService::class.java))
                window.decorView.postDelayed(::refreshStatus, 300)
            }
        }
        findViewById<android.view.View>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, ScreenshotWatcherService::class.java))
            window.decorView.postDelayed(::refreshStatus, 200)
        }
        findViewById<android.view.View>(R.id.cleanButton).setOnClickListener {
            val count = ScreenshotWatcherService.cleanTemporaryFiles(this, 0)
            Toast.makeText(this, "$count arquivo(s) removido(s)", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (settingsStep == 1 && Environment.isExternalStorageManager()) {
            settingsStep = 0
            window.decorView.post { openOverlaySettings() }
        } else settingsStep = 0
    }

    private fun runtimePermissions() = buildList {
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
        } else add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun requestNeededPermissions() {
        val missing = runtimePermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), requestCode)
        else requestSpecialPermission()
    }

    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        if (code == requestCode) requestSpecialPermission()
    }

    private fun requestSpecialPermission() {
        if (!Environment.isExternalStorageManager()) {
            settingsStep = 1
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } else if (!Settings.canDrawOverlays(this)) openOverlaySettings()
        else refreshStatus()
    }

    private fun openOverlaySettings() {
        if (Settings.canDrawOverlays(this)) return
        settingsStep = 2
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
        }.onFailure { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
    }

    private fun hasAllPermissions() = Environment.isExternalStorageManager() &&
        Settings.canDrawOverlays(this) &&
        runtimePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun refreshStatus() {
        findViewById<TextView>(R.id.serviceStatus).text =
            getString(R.string.service_status, if (ScreenshotWatcherService.isRunning) "ativo" else "parado")
        val runtime = runtimePermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        findViewById<TextView>(R.id.permissionStatus).text =
            getString(R.string.permission_status, if (runtime) "concedidas" else "pendentes",
                if (Environment.isExternalStorageManager()) "autorizado" else "não autorizado",
                if (Settings.canDrawOverlays(this)) "autorizado" else "não autorizado")
    }

}
