package org.kiz.boot.usb

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import org.kiz.boot.core.Format
import org.kiz.boot.core.WriterBus
import org.kiz.boot.fat32.Fat32Formatter
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Arrays

/** Операция прервана пользователем. */
class CancelledException : RuntimeException("Операция отменена пользователем")

/** Срез прогресса для UI и уведомления. */
data class ProgressInfo(
    val done: Long,
    val total: Long,
    val speedBytesPerSec: Double,
    val lba: Long
)

/** Скользящее окно скорости передачи. */
class SpeedMeter(private val windowMs: Long = SPEED_WINDOW_MS) {

    companion object {
        private const val SPEED_WINDOW_MS = 3000L
    }

    private data class Sample(val time: Long, val value: Long)
    private val samples = ArrayDeque<Sample>()

    fun sample(value: Long) {
        val now = SystemClock.elapsedRealtime()
        samples.addLast(Sample(now, value))
        while (samples.size > 2 && now - samples.first().time > windowMs) {
            samples.removeFirst()
        }
    }

    fun speedBytesPerSec(): Double {
        if (samples.size < 2) return 0.0
        val first = samples.first()
        val last = samples.last()
        val dt = last.time - first.time
        if (dt <= 0) return 0.0
        return (last.value - first.value) * 1000.0 / dt
    }
}

/**
 * Движок поблочной работы с носителем: потоковая запись образа из SAF-Uri,
 * сравнительная верификация, зануление таблицы разделов и разметка FAT32.
 *
 * Все операции выполняются блоками: из исходного файла читается буфер 1 МиБ
 * (константа [READ_BUFFER_BYTES_LARGE] — вариант 4 МиБ), который отправляется
 * SCSI-командами WRITE(10) по 128 КиБ (256 секторов) на транзакцию.
 */
class BootEngine(private val session: UsbSession) {

    companion object {
        /** Буфер чтения из исходного файла: 1 МиБ (1048576). */
        const val READ_BUFFER_BYTES = 1048576

        /** Альтернативный увеличенный буфер: 4 МиБ (4194304). */
        const val READ_BUFFER_BYTES_LARGE = 4194304

        /** Объём зануления в начале диска: 10 МиБ (MBR + GPT + запас). */
        const val WIPE_BYTES = 10485760L

        private const val LOG_STEP_BYTES = 8388608L
    }

    private val scsi = session.scsi

    init {
        scsi.readCapacity()
    }

    val blockSize: Int
        get() = scsi.blockSize

    fun totalCapacityBytes(): Long {
        val (lastLba, block) = scsi.readCapacity()
        return (lastLba + 1) * block
    }

    // ------------------------------------------------------------ запись образа

    /**
     * Записывает содержимое [uri] на носитель с LBA 0.
     * @param expectedSize точный размер исходного файла
     * @return записанный объём с учётом добивки последнего блока до границы сектора
     */
    fun writeImage(
        context: Context,
        uri: Uri,
        expectedSize: Long,
        onProgress: (ProgressInfo) -> Unit,
        checkCancelled: () -> Boolean
    ): Long {
        require(expectedSize > 0) { "Файл образа пуст" }
        val capacity = totalCapacityBytes()
        if (expectedSize > capacity) {
            throw IllegalArgumentException(
                "Образ (${Format.fileSize(expectedSize)}) больше ёмкости носителя (${Format.fileSize(capacity)})"
            )
        }
        val block = blockSize
        val totalToWrite = roundUpToSector(expectedSize, block)
        val commandBytes = ScsiTransport.SECTORS_PER_COMMAND * block
        val buffer = ByteArray(READ_BUFFER_BYTES)
        val meter = SpeedMeter()
        var lba = 0L
        var nextLogAt = LOG_STEP_BYTES

        WriterBus.log("Старт записи: ${Format.fileSize(expectedSize)}, сектор $block Б")
        context.contentResolver.openInputStream(uri)?.use { raw ->
            BufferedInputStream(raw, READ_BUFFER_BYTES).use { input ->
                while (true) {
                    checkCancelled()
                    val chunk = fillBuffer(input, buffer)
                    if (chunk == 0) break
                    var offset = 0
                    while (offset < chunk) {
                        checkCancelled()
                        val len = minOf(commandBytes, chunk - offset)
                        val padded = roundUpToSector(len.toLong(), block).toInt()
                        if (padded > len) Arrays.fill(buffer, len, padded, 0.toByte())
                        val sectors = padded / block
                        scsi.write10(lba, sectors, buffer, offset)
                        lba += sectors
                        meter.sample(lba * block)
                        onProgress(ProgressInfo(lba * block, totalToWrite, meter.speedBytesPerSec(), lba))
                        if (lba * block >= nextLogAt) {
                            nextLogAt += LOG_STEP_BYTES
                            WriterBus.log(
                                "LBA 0x%08X · %s · %s".format(
                                    lba, Format.fileSize(lba * block), Format.speed(meter.speedBytesPerSec())
                                )
                            )
                        }
                        offset += len
                    }
                }
            }
        } ?: throw IOException("Не удалось открыть поток чтения исходного файла")

        val written = lba * block
        if (written < totalToWrite) {
            throw IOException(
                "Файл закончился раньше времени: получено ${Format.fileSize(written)} " +
                    "из ${Format.fileSize(totalToWrite)}"
            )
        }
        flushCache(lba - 1)
        WriterBus.log("Записано ${Format.fileSize(written)}")
        return written
    }

    // ------------------------------------------------------------ верификация

    /**
     * Сравнительная верификация: повторно читает исходный файл и побайтово
     * сверяет его с READ(10) с устройства для записанного диапазона.
     */
    fun verifyImage(
        context: Context,
        uri: Uri,
        writtenBytes: Long,
        onProgress: (ProgressInfo) -> Unit,
        checkCancelled: () -> Boolean
    ) {
        val block = blockSize
        val totalToCheck = roundUpToSector(writtenBytes, block)
        val commandBytes = ScsiTransport.SECTORS_PER_COMMAND * block
        val srcBuffer = ByteArray(READ_BUFFER_BYTES)
        val deviceBuffer = ByteArray(READ_BUFFER_BYTES)
        val meter = SpeedMeter()
        var lba = 0L

        WriterBus.log("Верификация: сверка ${Format.fileSize(totalToCheck)}")
        context.contentResolver.openInputStream(uri)?.use { raw ->
            BufferedInputStream(raw, READ_BUFFER_BYTES).use { input ->
                while (lba * block < totalToCheck) {
                    checkCancelled()
                    val chunk = fillBuffer(input, srcBuffer)
                    if (chunk == 0) throw IOException("Исходный файл стал короче при верификации")
                    var offset = 0
                    while (offset < chunk) {
                        checkCancelled()
                        val len = minOf(commandBytes, chunk - offset)
                        val padded = roundUpToSector(len.toLong(), block).toInt()
                        if (padded > len) Arrays.fill(srcBuffer, len, padded, 0.toByte())
                        val sectors = padded / block
                        scsi.read10(lba, sectors, deviceBuffer, 0)
                        val mismatch = firstMismatch(srcBuffer, offset, padded, deviceBuffer)
                        if (mismatch >= 0) {
                            throw ScsiException(
                                "Верификация провалена: сектор ${lba + mismatch / block} " +
                                    "не совпадает с исходным файлом"
                            )
                        }
                        lba += sectors
                        meter.sample(lba * block)
                        onProgress(ProgressInfo(lba * block, totalToCheck, meter.speedBytesPerSec(), lba))
                        offset += len
                    }
                }
            }
        } ?: throw IOException("Не удалось открыть поток чтения исходного файла")
        WriterBus.log("Верификация пройдена: данные идентичны")
    }

    // ------------------------------------------------------------ очистка и разметка

    /** Зануление первых 10 МиБ диска: MBR, GPT и остатки любой старой разметки. */
    fun wipePartitionTable(onProgress: (ProgressInfo) -> Unit, checkCancelled: () -> Boolean) {
        val block = blockSize
        val commandBytes = ScsiTransport.SECTORS_PER_COMMAND * block
        val zero = ByteArray(commandBytes)
        val total = WIPE_BYTES
        var wiped = 0L
        var lba = 0L

        WriterBus.log("Зануление первых ${Format.fileSize(total)} (MBR/GPT)")
        while (wiped < total) {
            checkCancelled()
            val step = minOf(commandBytes.toLong(), total - wiped)
            val sectors = (step / block).toInt()
            scsi.write10(lba, sectors, zero, 0)
            lba += sectors
            wiped += sectors * block
            onProgress(ProgressInfo(wiped, total, 0.0, lba))
        }
        flushCache(lba - 1)
        WriterBus.log("Таблица разделов занулена")
    }

    /** Форматирование носителя в FAT32 (MBR + один раздел на весь объём). */
    fun formatFat32(onProgress: (ProgressInfo) -> Unit, checkCancelled: () -> Boolean) {
        val (lastLba, block) = scsi.readCapacity()
        val lastWritten = Fat32Formatter(scsi, block, lastLba + 1).format(onProgress, checkCancelled)
        flushCache(lastWritten)
    }

    /** SYNCHRONIZE CACHE(10) по диапазону порциями не длиннее 65535 секторов. */
    fun flushCache(lastLba: Long) {
        var lba = 0L
        while (lba <= lastLba) {
            val count = minOf(65535L, lastLba - lba + 1).toInt()
            scsi.synchronizeCache10(lba, count)
            lba += count
        }
    }

    // ------------------------------------------------------------ служебное

    /** Читает из потока ровно buffer.size байт либо до конца файла. */
    private fun fillBuffer(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private fun roundUpToSector(bytes: Long, block: Int): Long {
        val remainder = bytes % block
        return if (remainder == 0L) bytes else bytes + block - remainder
    }

    /** Первый несовпавший байт диапазона либо -1. */
    private fun firstMismatch(a: ByteArray, aOffset: Int, length: Int, b: ByteArray): Int {
        for (i in 0 until length) {
            if (a[aOffset + i] != b[i]) return i
        }
        return -1
    }
}
