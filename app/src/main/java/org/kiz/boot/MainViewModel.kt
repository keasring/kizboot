package org.kiz.boot

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.kiz.boot.core.UsbFlashDrive
import org.kiz.boot.core.WriterBus
import org.kiz.boot.service.BootWriterService
import org.kiz.boot.usb.UsbController

/** Выбранный пользовательский образ. */
data class SelectedImage(val uri: Uri, val name: String, val size: Long)

/**
 * Экранная модель: список накопителей, выбранный образ, настройки,
 * отправка команд в BootWriterService.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val usb = UsbController(app)

    var selectedImage by mutableStateOf<SelectedImage?>(null)
        private set
    var drives by mutableStateOf<List<UsbFlashDrive>>(emptyList())
        private set
    var capacities by mutableStateOf<Map<Int, Long>>(emptyMap())
        private set
    var selectedDriveId by mutableStateOf<Int?>(null)
    var keepScreenOn by mutableStateOf(true)
    var verify by mutableStateOf(true)

    val isBusy: Boolean
        get() = WriterBus.state.value.busy

    val canWrite: Boolean
        get() {
            if (isBusy) return false
            val image = selectedImage ?: return false
            val drive = drives.firstOrNull { it.deviceId == selectedDriveId } ?: return false
            if (!drive.hasPermission) return false
            val capacity = capacities[drive.deviceId] ?: return true // ёмкость перепроверит движок
            return image.size < capacity
        }

    val canFormat: Boolean
        get() {
            if (isBusy) return false
            val drive = drives.firstOrNull { it.deviceId == selectedDriveId } ?: return false
            return drive.hasPermission
        }

    init {
        usb.register()
        usb.onDeviceEvent = { refreshDrives() }
        refreshDrives()
    }

    override fun onCleared() {
        usb.unregister()
    }

    fun refreshDrives() {
        drives = usb.listDrives()
        if (drives.none { it.deviceId == selectedDriveId }) {
            selectedDriveId = drives.firstOrNull()?.deviceId
        }
        probeCapacities()
    }

    fun selectDrive(deviceId: Int) {
        selectedDriveId = deviceId
    }

    /** Накопитель подключён (или приложение открыто по USB_DEVICE_ATTACHED). */
    fun onDeviceAttached(device: UsbDevice) {
        refreshDrives()
        if (drives.any { it.deviceId == device.deviceId }) {
            selectedDriveId = device.deviceId
        }
    }

    /** Обработка результата SAF-пикера: сохраняем Uri и читаем имя/размер. */
    fun onImagePicked(uri: Uri, resolver: ContentResolver) {
        runCatching {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModelScope.launch(Dispatchers.IO) {
            var name = "образ.bin"
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            name = cursor.getString(0) ?: name
                            size = if (!cursor.isNull(1)) cursor.getLong(1) else -1L
                        }
                    }
            }
            if (size <= 0) {
                runCatching {
                    resolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
                }
            }
            if (size > 0) {
                selectedImage = SelectedImage(uri, name, size)
            } else {
                WriterBus.log("Не удалось прочитать выбранный файл")
            }
        }
    }

    fun requestPermission(drive: UsbFlashDrive) {
        val device = usb.findDevice(drive.deviceId) ?: return
        usb.requestPermission(device) { granted ->
            if (!granted) WriterBus.log("Доступ к накопителю не предоставлен")
            refreshDrives()
        }
    }

    fun startWrite() {
        val image = selectedImage ?: return
        val drive = drives.firstOrNull { it.deviceId == selectedDriveId } ?: return
        if (!drive.hasPermission) return
        val intent = Intent(getApplication(), BootWriterService::class.java)
            .setAction(BootWriterService.ACTION_WRITE)
            .putExtra(BootWriterService.EXTRA_URI, image.uri)
            .putExtra(BootWriterService.EXTRA_DEVICE_ID, drive.deviceId)
            .putExtra(BootWriterService.EXTRA_VERIFY, verify)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun cancelOperation() {
        val intent = Intent(getApplication(), BootWriterService::class.java)
            .setAction(BootWriterService.ACTION_CANCEL)
        runCatching { getApplication<Application>().startService(intent) }
    }

    fun startFormat() {
        val drive = drives.firstOrNull { it.deviceId == selectedDriveId } ?: return
        if (!drive.hasPermission) return
        val intent = Intent(getApplication(), BootWriterService::class.java)
            .setAction(BootWriterService.ACTION_FORMAT)
            .putExtra(BootWriterService.EXTRA_DEVICE_ID, drive.deviceId)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    /** Для накопителей с выданным разрешением уточняет ёмкость через READ CAPACITY. */
    private fun probeCapacities() {
        viewModelScope.launch(Dispatchers.IO) {
            val result = mutableMapOf<Int, Long>()
            for (drive in drives) {
                if (!drive.hasPermission || WriterBus.state.value.busy) continue
                val device = usb.findDevice(drive.deviceId) ?: continue
                runCatching { usb.probeCapacity(device) }.getOrNull()?.let { result[drive.deviceId] = it }
            }
            capacities = result
        }
    }
}
