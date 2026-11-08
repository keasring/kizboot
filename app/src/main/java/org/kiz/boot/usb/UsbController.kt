package org.kiz.boot.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.kiz.boot.core.UsbFlashDrive

/** Открытая сессия обмена с носителем: соединение, захваченный интерфейс и SCSI-транспорт. */
class UsbSession(
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    val scsi: ScsiTransport
) : AutoCloseable {

    override fun close() {
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }
}

/**
 * Поиск накопителей, запрос системного разрешения и установка соединения.
 * Один экземпляр на компонент (ViewModel или Service).
 */
class UsbController(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "org.kiz.boot.action.USB_PERMISSION"
        private const val CLASS_MASS_STORAGE = 0x08
        private const val READY_ATTEMPTS = 10
        private const val READY_DELAY_MS = 300L
    }

    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var receiverRegistered = false
    private var permissionReceiverRegistered = false

    /** Колбэк для UI: накопитель подключён или извлечён — пора обновить список. */
    var onDeviceEvent: (() -> Unit)? = null

    /** Колбэк результата requestPermission (вызывается на главном потоке). */
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val deviceEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED
            ) {
                onDeviceEvent?.invoke()
            }
        }
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val callback = permissionCallback
            permissionCallback = null
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            callback?.invoke(granted)
        }
    }

    /** Регистрирует приёмник событий подключения/извлечения (вызвать из Activity/VM). */
    fun register() {
        if (receiverRegistered) return
        receiverRegistered = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        // Оба события — защищённые системные броадкасты, NOT_EXPORTED достаточно.
        ContextCompat.registerReceiver(context, deviceEventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registerPermissionReceiver()
    }

    fun unregister() {
        if (receiverRegistered) {
            receiverRegistered = false
            runCatching { context.unregisterReceiver(deviceEventReceiver) }
        }
        if (permissionReceiverRegistered) {
            permissionReceiverRegistered = false
            runCatching { context.unregisterReceiver(permissionReceiver) }
        }
        onDeviceEvent = null
        permissionCallback = null
    }

    private fun registerPermissionReceiver() {
        if (permissionReceiverRegistered) return
        permissionReceiverRegistered = true
        // EXPORTED: системный UsbManager доставляет ответ через PendingIntent и на
        // части прошивок ожидает открытый приёмник; интент создан с setPackage,
        // поэтому посторонние приложения безвредны.
        ContextCompat.registerReceiver(
            context, permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_EXPORTED
        )
    }

    /** Все подключённые накопители с классом Mass Storage. */
    fun listDrives(): List<UsbFlashDrive> =
        usbManager.deviceList.values
            .filter { isMassStorage(it) }
            .map { d ->
                UsbFlashDrive(
                    deviceId = d.deviceId,
                    vendorId = d.vendorId,
                    productId = d.productId,
                    vendorName = d.manufacturerName?.takeIf { it.isNotBlank() }
                        ?: "Устройство %04X".format(d.vendorId),
                    productName = d.productName?.takeIf { it.isNotBlank() } ?: "USB Mass Storage",
                    hasPermission = usbManager.hasPermission(d)
                )
            }
            .sortedBy { it.deviceId }

    fun findDevice(deviceId: Int): UsbDevice? =
        usbManager.deviceList.values.firstOrNull { it.deviceId == deviceId }

    fun isMassStorage(device: UsbDevice): Boolean {
        if (device.deviceClass == CLASS_MASS_STORAGE) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == CLASS_MASS_STORAGE) return true
        }
        return false
    }

    /**
     * Запрашивает системное разрешение на доступ к устройству.
     * Результат приходит в [onResult] на главном потоке.
     */
    fun requestPermission(device: UsbDevice, onResult: (Boolean) -> Unit) {
        registerPermissionReceiver()
        permissionCallback = onResult
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, flags))
    }

    /**
     * Открывает сессию: соединение, захват интерфейса, SCSI-транспорт,
     * ожидание готовности носителя (сброс UNIT ATTENTION после подключения).
     */
    fun openSession(device: UsbDevice): UsbSession {
        val connection = usbManager.openDevice(device)
            ?: throw IllegalStateException(
                "Не удалось открыть USB-устройство: разрешение не выдано или накопитель извлечён"
            )
        try {
            val iface = findMassStorageInterface(device)
            if (!connection.claimInterface(iface, true)) {
                throw IllegalStateException("Не удалось захватить USB-интерфейс")
            }
            val bulkIn = findBulkEndpoint(iface, UsbConstants.USB_DIR_IN)
                ?: throw IllegalStateException("Bulk IN эндпоинт не найден")
            val bulkOut = findBulkEndpoint(iface, UsbConstants.USB_DIR_OUT)
                ?: throw IllegalStateException("Bulk OUT эндпоинт не найден")
            val scsi = ScsiTransport(connection, bulkIn, bulkOut, iface)
            waitReady(scsi)
            return UsbSession(device, connection, iface, scsi)
        } catch (e: Exception) {
            runCatching { connection.close() }
            throw e
        }
    }

    /** Быстрое чтение ёмкости для списка устройств (сессия открывается и закрывается). */
    fun probeCapacity(device: UsbDevice): Long? = try {
        openSession(device).use { session ->
            val (lastLba, block) = session.scsi.readCapacity()
            (lastLba + 1) * block
        }
    } catch (e: Exception) {
        null
    }

    private fun findMassStorageInterface(device: UsbDevice): UsbInterface {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == CLASS_MASS_STORAGE) return iface
        }
        return device.getInterface(0)
    }

    private fun findBulkEndpoint(iface: UsbInterface, direction: Int): UsbEndpoint? {
        for (e in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(e)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == direction) return ep
        }
        return null
    }

    private fun waitReady(scsi: ScsiTransport) {
        for (attempt in 1..READY_ATTEMPTS) {
            if (scsi.testUnitReady()) return
            if (attempt == 1) scsi.massStorageReset()
            SystemClock.sleep(READY_DELAY_MS)
        }
        val sense = runCatching { scsi.requestSense() }.getOrNull()
        throw IllegalStateException("Носитель не готов" + (sense?.let { " ($it)" } ?: ""))
    }
}
