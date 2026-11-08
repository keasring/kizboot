package org.kiz.boot.core

import androidx.compose.runtime.Immutable

/** Сводка по найденному USB-накопителю (без доступа к данным). */
@Immutable
data class UsbFlashDrive(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val vendorName: String,
    val productName: String,
    val hasPermission: Boolean
)

/** Фаза фоновой операции. */
enum class Phase { IDLE, PREPARING, WRITING, VERIFYING, FORMATTING, DONE, ERROR }

/**
 * Состояние операции записи: публикуется сервисом, наблюдается UI.
 * Во время верификации [writtenBytes] содержит объём проверенных байт.
 */
data class WriterState(
    val phase: Phase = Phase.IDLE,
    val fileName: String = "",
    val totalBytes: Long = 0L,
    val writtenBytes: Long = 0L,
    val speedMbPerSec: Double = 0.0,
    val etaSeconds: Long = -1L,
    val currentLba: Long = -1L,
    val message: String = ""
) {
    val percent: Int
        get() = if (totalBytes <= 0) 0 else ((writtenBytes * 100) / totalBytes).toInt().coerceIn(0, 100)

    val busy: Boolean
        get() = phase in BUSY_PHASES

    companion object {
        private val BUSY_PHASES = setOf(Phase.PREPARING, Phase.WRITING, Phase.VERIFYING, Phase.FORMATTING)
    }
}
