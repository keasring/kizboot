package org.kiz.boot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import org.kiz.boot.ui.MainScreen
import org.kiz.boot.ui.theme.KizBootTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    /** SAF-пикер: образы дисков. Первый mime — общий фильтр, остальные — подсказки типов. */
    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onImagePicked(uri, contentResolver)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* сервис работает и без уведомлений */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        handleDeviceIntent(intent)
        setContent {
            KizBootTheme {
                MainScreen(vm = vm, onPickImage = ::pickImage)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeviceIntent(intent)
    }

    /** Приложение открыто по подключению накопителя из device_filter.xml. */
    private fun handleDeviceIntent(intent: Intent?) {
        val device = intent?.let {
            IntentCompat.getParcelableExtra(it, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        }
        if (device != null) vm.onDeviceAttached(device)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun pickImage() {
        imagePicker.launch(
            arrayOf(
                "*/*",
                "application/x-iso9660-image",
                "application/octet-stream",
                "application/x-cd-image",
                "application/x-raw-disk-image"
            )
        )
    }
}
