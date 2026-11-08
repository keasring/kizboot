package org.kiz.boot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.kiz.boot.MainActivity
import org.kiz.boot.R
import org.kiz.boot.core.Format
import org.kiz.boot.core.Phase
import org.kiz.boot.core.WriterBus
import org.kiz.boot.core.WriterState
import org.kiz.boot.usb.BootEngine
import org.kiz.boot.usb.CancelledException
import org.kiz.boot.usb.ProgressInfo
import org.kiz.boot.usb.UsbController
import org.kiz.boot.usb.UsbSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-сервис записи: удерживает WakeLock и уведомление с прогресс-баром,
 * выполняет запись, верификацию и форматирование на Dispatchers.IO.
 *
 * Тип сервиса — dataSync, чтобы система не убивала процесс в фоне при блокировке
 * экрана; отмена операции идёт через ACTION_CANCEL и AtomicBoolean-флаг, который
 * движок проверяет между блоками передачи.
 */
class BootWriterService : Service() {

    companion object {
        const val ACTION_WRITE = "org.kiz.boot.action.WRITE"
        const val ACTION_CANCEL = "org.kiz.boot.action.CANCEL"
        const val ACTION_FORMAT = "org.kiz.boot.action.FORMAT"

        const val EXTRA_URI = "extra.uri"
        const val EXTRA_DEVICE_ID = "extra.deviceId"
        const val EXTRA_VERIFY = "extra.verify"

        private const val CHANNEL_ID = "kizboot.writer"
        private const val PROGRESS_NOTIFICATION_ID = 1001
        private const val FINAL_NOTIFICATION_ID = 1002
        private const val NOTIFICATION_UPDATE_MS = 250L
        private const val WAKELOCK_TIMEOUT_MS = 6L * 60 * 60 * 1000 // предохранитель на 6 часов
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var session: UsbSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationUpdate = 0L
    private var detachRegistered = false

    /** Внезапное извлечение носителя во время операции. */
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val detached = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            val current = session?.device ?: return
            if (detached != null && detached.deviceId == current.deviceId) {
                WriterBus.log("Носитель извлечён")
                running.set(false)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // Каждому startForegroundService обязан соответствовать вызов startForeground,
        // поэтому выполняем его при любом действии.
        startForegroundCompat(
            progressNotification(
                title = getString(R.string.app_name),
                text = getString(R.string.notif_preparing),
                percent = 0,
                indeterminate = true
            )
        )

        when (intent?.action) {
            ACTION_CANCEL -> {
                running.set(false)
                WriterBus.log("Запрошена отмена операции")
            }
            ACTION_WRITE -> {
                val uri = IntentCompat.getParcelableExtra(intent, EXTRA_URI, Uri::class.java)
                val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                val verify = intent.getBooleanExtra(EXTRA_VERIFY, true)
                if (uri != null && deviceId > 0) startWrite(uri, deviceId, verify)
            }
            ACTION_FORMAT -> {
                val deviceId = intent.getIntExtra(EXTRA_DEVICE_ID, -1)
                if (deviceId > 0) startFormat(deviceId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        scope.cancel()
        unregisterDetach()
        releaseWakeLock()
        session?.close()
        session = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ запись образа

    private fun startWrite(uri: Uri, deviceId: Int, verify: Boolean) {
        if (!running.compareAndSet(false, true)) {
            WriterBus.log("Уже выполняется другая операция")
            return
        }
        beginOperation()
        val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "образ"
        scope.launch {
            try {
                WriterBus.set(WriterState(phase = Phase.PREPARING, fileName = fileName))
                WriterBus.log("Подготовка к записи…")
                val usb = UsbController(applicationContext)
                val device = usb.findDevice(deviceId)
                    ?: throw IllegalStateException("USB-накопитель больше не подключён")
                val opened = usb.openSession(device)
                session = opened
                val engine = BootEngine(opened)

                runCatching { opened.scsi.inquiry() }.getOrNull()?.let {
                    WriterBus.log("Носитель: ${it.vendor} ${it.product}")
                }
                WriterBus.log(
                    "Ёмкость: ${Format.fileSize(engine.totalCapacityBytes())}, сектор ${engine.blockSize} Б"
                )

                val fileSize = querySize(uri)
                WriterBus.update { it.copy(totalBytes = fileSize, fileName = fileName, phase = Phase.WRITING) }

                val written = engine.writeImage(
                    applicationContext, uri, fileSize,
                    onProgress = { p -> onProgress(p, Phase.WRITING, fileName) },
                    checkCancelled = { !running.get() }
                )

                if (verify) {
                    WriterBus.update { it.copy(phase = Phase.VERIFYING) }
                    engine.verifyImage(
                        applicationContext, uri, written,
                        onProgress = { p -> onProgress(p, Phase.VERIFYING, fileName) },
                        checkCancelled = { !running.get() }
                    )
                    finishSuccess(getString(R.string.notif_done), "Образ записан и верифицирован (${Format.fileSize(written)})")
                } else {
                    finishSuccess(getString(R.string.notif_done), "Образ записан: ${Format.fileSize(written)}")
                }
            } catch (e: CancelledException) {
                WriterBus.log("Носитель в несогласованном состоянии — запишите образ заново")
                finish(Phase.IDLE, "Операция отменена")
            } catch (e: Exception) {
                WriterBus.log("Ошибка: ${e.message ?: e.javaClass.simpleName}")
                finish(Phase.ERROR, e.message ?: "Неизвестная ошибка")
            } finally {
                endOperation()
            }
        }
    }

    // ------------------------------------------------------------ форматирование

    private fun startFormat(deviceId: Int) {
        if (!running.compareAndSet(false, true)) {
            WriterBus.log("Уже выполняется другая операция")
            return
        }
        beginOperation()
        scope.launch {
            try {
                WriterBus.set(WriterState(phase = Phase.FORMATTING, fileName = "FAT32"))
                val usb = UsbController(applicationContext)
                val device = usb.findDevice(deviceId)
                    ?: throw IllegalStateException("USB-накопитель больше не подключён")
                val opened = usb.openSession(device)
                session = opened
                val engine = BootEngine(opened)

                engine.wipePartitionTable(
                    onProgress = { p -> onProgress(p, Phase.FORMATTING, "Очистка MBR/GPT") },
                    checkCancelled = { !running.get() }
                )
                engine.formatFat32(
                    onProgress = { p -> onProgress(p, Phase.FORMATTING, "Разметка FAT32") },
                    checkCancelled = { !running.get() }
                )
                finishSuccess(getString(R.string.notif_done), "Носитель отформатирован в FAT32")
            } catch (e: CancelledException) {
                WriterBus.log("Носитель в несогласованном состоянии — повторите форматирование")
                finish(Phase.IDLE, "Операция отменена")
            } catch (e: Exception) {
                WriterBus.log("Ошибка: ${e.message ?: e.javaClass.simpleName}")
                finish(Phase.ERROR, e.message ?: "Неизвестная ошибка")
            } finally {
                endOperation()
            }
        }
    }

    // ------------------------------------------------------------ служебное

    private fun beginOperation() {
        registerDetach()
        WriterBus.clearLog()
        acquireWakeLock()
    }

    private fun endOperation() {
        session?.close()
        session = null
        releaseWakeLock()
        unregisterDetach()
        running.set(false)
    }

    private fun querySize(uri: Uri): Long =
        contentResolver.openFileDescriptor(uri, "r")?.use { pfd: ParcelFileDescriptor -> pfd.statSize }
            ?: throw IllegalStateException("Не удалось прочитать размер файла")

    private fun onProgress(p: ProgressInfo, phase: Phase, label: String) {
        WriterBus.update {
            it.copy(
                phase = phase,
                fileName = label,
                writtenBytes = p.done,
                totalBytes = p.total,
                speedMbPerSec = p.speedBytesPerSec / (1024.0 * 1024.0),
                etaSeconds = etaSeconds(p),
                currentLba = p.lba
            )
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdate >= NOTIFICATION_UPDATE_MS) {
            lastNotificationUpdate = now
            val state = WriterBus.state.value
            notificationManager().notify(
                PROGRESS_NOTIFICATION_ID,
                progressNotification(label, "${state.percent}% · ${Format.speed(p.speedBytesPerSec)}", state.percent, false)
            )
        }
    }

    private fun etaSeconds(p: ProgressInfo): Long {
        val remaining = p.total - p.done
        if (remaining <= 0 || p.speedBytesPerSec < 1.0) return -1
        return (remaining / p.speedBytesPerSec).toLong()
    }

    private fun finishSuccess(title: String, message: String) {
        WriterBus.log("Готово: $message")
        WriterBus.update {
            it.copy(phase = Phase.DONE, message = message, speedMbPerSec = 0.0, etaSeconds = -1)
        }
        showFinalNotification(title, message)
        stopForegroundInternal()
    }

    private fun finish(phase: Phase, message: String) {
        WriterBus.update {
            it.copy(phase = phase, message = message, speedMbPerSec = 0.0, etaSeconds = -1)
        }
        if (phase == Phase.ERROR) {
            showFinalNotification(getString(R.string.notif_error), message)
        }
        stopForegroundInternal()
    }

    private fun stopForegroundInternal() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------ уведомления

    private fun progressNotification(title: String, text: String, percent: Int, indeterminate: Boolean): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BootWriterService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_writer)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_stat_writer,
                    getString(R.string.action_cancel),
                    cancelIntent
                ).build()
            )
            .build()
    }

    private fun showFinalNotification(title: String, text: String) {
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_writer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        notificationManager().notify(FINAL_NOTIFICATION_ID, notification)
    }

    private fun notificationManager() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = getString(R.string.notif_channel_desc)
        notificationManager().createNotificationChannel(channel)
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    // ------------------------------------------------------------ WakeLock

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kizBoot:writer").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    // ------------------------------------------------------------ приёмники

    private fun registerDetach() {
        if (detachRegistered) return
        detachRegistered = true
        ContextCompat.registerReceiver(
            this,
            detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterDetach() {
        if (!detachRegistered) return
        detachRegistered = false
        runCatching { unregisterReceiver(detachReceiver) }
    }
}
