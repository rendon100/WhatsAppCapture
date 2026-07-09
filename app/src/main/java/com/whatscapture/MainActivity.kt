package com.whatscapture

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var btnPermissionNotify: Button
    private lateinit var btnPermissionStorage: Button
    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Config.init(this)

        btnPermissionNotify = findViewById(R.id.btnPermissionNotify)
        btnPermissionStorage = findViewById(R.id.btnPermissionStorage)
        btnStart = findViewById(R.id.btnStart)

        btnPermissionNotify.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        btnPermissionStorage.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            } else {
                requestStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        btnStart.setOnClickListener {
            if (!isNotificationServiceEnabled()) {
                Toast.makeText(this, "Activa el acceso a notificaciones en Ajustes", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                return@setOnClickListener
            }
            startService(Intent(this, MediaMonitorService::class.java))
            Toast.makeText(this, "Servicio iniciado en segundo plano", Toast.LENGTH_SHORT).show()
            btnStart.isEnabled = false
            btnStart.text = "EN EJECUCIÓN"
        }

        if (Config.isRunning(this)) {
            btnStart.isEnabled = false
            btnStart.text = "EN EJECUCIÓN"
        }

        checkInitialPermissions()
    }

    private fun checkInitialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val requestStorageLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun isNotificationServiceEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        )
        return enabledListeners?.contains(packageName) == true
    }
}
