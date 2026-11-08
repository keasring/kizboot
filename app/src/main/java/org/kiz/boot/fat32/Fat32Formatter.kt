package org.kiz.boot.fat32

import org.kiz.boot.core.Bytes
import org.kiz.boot.core.Format
import org.kiz.boot.core.WriterBus
import org.kiz.boot.usb.ProgressInfo
import org.kiz.boot.usb.ScsiTransport

/**
 * Форматирование носителя в FAT32: MBR с одним разделом (тип 0x0C),
 * загрузочный сектор с BPB, FSInfo, резервная копия VBR, две таблицы FAT
 * и корневой каталог с меткой тома KIZBOOT.
 */
class Fat32Formatter(
    private val scsi: ScsiTransport,
    private val blockSize: Int,
    private val totalSectors: Long
) {

    companion object {
        private const val PARTITION_START_LBA = 2048L // выравнивание раздела на 1 МиБ
        private const val RESERVED_SECTORS = 32
        private const val FAT_COUNT = 2
        private const val VOLUME_LABEL = "KIZBOOT"
        private const val MAX_FAT32_SECTORS = 0xFFFFFFF7L
    }

    private var partSectors = 0L
    private var clusterSize = 0
    private var fatSectors = 0L

    /**
     * Выполняет форматирование.
     * @return последний записанный LBA (для сброса кэша устройства)
     */
    fun format(onProgress: (ProgressInfo) -> Unit, checkCancelled: () -> Boolean): Long {
        partSectors = totalSectors - PARTITION_START_LBA
        require(partSectors > 0) { "Носитель слишком мал для FAT32" }
        require(totalSectors <= MAX_FAT32_SECTORS) { "Носитель слишком велик для FAT32 (лимит 2 ТиБ)" }
        clusterSize = sectorsPerCluster(partSectors)
        fatSectors = computeFatSize(partSectors, clusterSize)

        val workSectors = 1L + RESERVED_SECTORS + FAT_COUNT * fatSectors + clusterSize
        var doneSectors = 0L
        fun report(step: Long) {
            doneSectors += step
            onProgress(
                ProgressInfo(
                    done = doneSectors * blockSize,
                    total = workSectors * blockSize,
                    speedBytesPerSec = 0.0,
                    lba = PARTITION_START_LBA + doneSectors
                )
            )
        }

        WriterBus.log(
            "FAT32: ${Format.fileSize(totalSectors * blockSize)}, кластер ${clusterSize * blockSize} Б, " +
                "FAT ${Format.fileSize(fatSectors * blockSize)} × $FAT_COUNT"
        )

        // 1) MBR в LBA 0 диска.
        checkCancelled()
        scsi.write10(0, 1, buildMbr(), 0)
        report(1)

        // 2) Заголовок раздела: VBR, FSInfo, нули, резервные копии (32 сектора).
        writeAll(
            buildPartitionHeader(),
            PARTITION_START_LBA,
            RESERVED_SECTORS.toLong(),
            ::report,
            checkCancelled
        )

        // 3) Две таблицы FAT (заголовок + нулевое тело).
        writeFat(PARTITION_START_LBA + RESERVED_SECTORS, ::report, checkCancelled)
        writeFat(PARTITION_START_LBA + RESERVED_SECTORS + fatSectors, ::report, checkCancelled)

        // 4) Корневой каталог — кластер 2 с меткой тома.
        val rootLba = PARTITION_START_LBA + RESERVED_SECTORS + FAT_COUNT * fatSectors
        writeAll(buildRootDir(), rootLba, clusterSize.toLong(), ::report, checkCancelled)

        WriterBus.log("FAT32: разметка завершена")
        return rootLba + clusterSize - 1
    }

    // ------------------------------------------------------------ запись

    private fun writeAll(
        data: ByteArray,
        startLba: Long,
        sectors: Long,
        report: (Long) -> Unit,
        checkCancelled: () -> Boolean
    ) {
        check(data.size.toLong() == sectors * blockSize) { "Размер буфера не равен диапазону записи" }
        var offset = 0
        var current = startLba
        var remaining = sectors
        while (remaining > 0) {
            checkCancelled()
            val step = minOf(ScsiTransport.SECTORS_PER_COMMAND.toLong(), remaining).toInt()
            scsi.write10(current, step, data, offset)
            offset += step * blockSize
            current += step
            remaining -= step
            report(step.toLong())
        }
    }

    /**
     * Пишет таблицу FAT без выделения её целиком в памяти: первый чанк содержит
     * заголовок FAT (дескриптор носителя, EOC, конец цепочки корня), остальное — нули.
     */
    private fun writeFat(startLba: Long, report: (Long) -> Unit, checkCancelled: () -> Boolean) {
        val chunkSectors = ScsiTransport.SECTORS_PER_COMMAND
        val chunk = ByteArray(chunkSectors * blockSize)
        System.arraycopy(buildFatFirstSector(), 0, chunk, 0, blockSize)
        var current = startLba
        var remaining = fatSectors
        while (remaining > 0) {
            checkCancelled()
            val step = minOf(chunkSectors.toLong(), remaining).toInt()
            scsi.write10(current, step, chunk, 0)
            current += step
            remaining -= step
            report(step.toLong())
        }
    }

    // ------------------------------------------------------------ структуры

    private fun buildMbr(): ByteArray {
        val b = ByteArray(blockSize)
        val e = 446
        b[e] = 0x00                                            // раздел не активен
        b[e + 1] = 0x00; b[e + 2] = 0x02; b[e + 3] = 0x00      // CHS начала: цил. 0, гол. 0, сект. 2
        b[e + 4] = 0x0C.toByte()                               // FAT32 с LBA-адресацией
        b[e + 5] = 0xFE.toByte(); b[e + 6] = 0xFF.toByte(); b[e + 7] = 0xFF.toByte() // CHS конца
        Bytes.putLe32(b, e + 8, PARTITION_START_LBA)
        Bytes.putLe32(b, e + 12, partSectors)
        b[510] = 0x55.toByte()
        b[511] = 0xAA.toByte()
        return b
    }

    /** 32 сектора: VBR, FSInfo, 4 нуля, резервная копия VBR (сектор 6), копия FSInfo (сектор 7), нули. */
    private fun buildPartitionHeader(): ByteArray {
        val b = ByteArray(RESERVED_SECTORS * blockSize)
        val vbr = buildVbr()
        val fsInfo = buildFsInfo()
        System.arraycopy(vbr, 0, b, 0, blockSize)
        System.arraycopy(fsInfo, 0, b, 1 * blockSize, blockSize)
        System.arraycopy(vbr, 0, b, 6 * blockSize, blockSize)
        System.arraycopy(fsInfo, 0, b, 7 * blockSize, blockSize)
        return b
    }

    private fun buildVbr(): ByteArray {
        val b = ByteArray(blockSize)
        b[0] = 0xEB.toByte(); b[1] = 0x3C; b[2] = 0x90.toByte() // JMP + NOP
        Bytes.putAscii(b, 3, "MSWIN4.1", 8)                     // OEM-строка
        Bytes.putLe16(b, 11, blockSize)                         // байт на сектор
        b[13] = clusterSize.toByte()                            // секторов на кластер
        Bytes.putLe16(b, 14, RESERVED_SECTORS)                  // резервные секторы
        b[16] = FAT_COUNT.toByte()                              // число FAT
        Bytes.putLe16(b, 17, 0)                                 // корневых записей (0 у FAT32)
        Bytes.putLe16(b, 19, 0)                                 // всего секторов 16-бит (0)
        b[21] = 0xF8.toByte()                                   // носитель: фиксированный диск
        Bytes.putLe16(b, 22, 0)                                 // FAT 16-бит (0 у FAT32)
        Bytes.putLe16(b, 24, 63)                                // секторов на дорожку
        Bytes.putLe16(b, 26, 255)                               // головок
        Bytes.putLe32(b, 28, PARTITION_START_LBA)               // скрытых секторов
        Bytes.putLe32(b, 32, partSectors)                       // всего секторов
        Bytes.putLe32(b, 36, fatSectors)                        // секторов на FAT
        Bytes.putLe16(b, 40, 0)                                 // ext flags: обе FAT активны
        Bytes.putLe16(b, 42, 0)                                 // версия FS
        Bytes.putLe32(b, 44, 2)                                 // кластер корневого каталога
        Bytes.putLe16(b, 48, 1)                                 // сектор FSInfo
        Bytes.putLe16(b, 50, 6)                                 // сектор резервной копии VBR
        b[64] = 0x80.toByte()                                   // номер диска
        b[65] = 0x00                                            // зарезервировано
        b[66] = 0x29.toByte()                                   // расширенная загрузочная подпись
        Bytes.putLe32(b, 67, volumeId())                        // серийный номер тома
        Bytes.putAscii(b, 71, VOLUME_LABEL.padEnd(11), 11)      // метка тома
        Bytes.putAscii(b, 82, "FAT32", 8)                       // тип ФС
        b[510] = 0x55.toByte()
        b[511] = 0xAA.toByte()
        return b
    }

    private fun buildFsInfo(): ByteArray {
        val b = ByteArray(blockSize)
        Bytes.putLe32(b, 0, 0x41615252L)          // 'RRaA'
        Bytes.putLe32(b, 484, 0x61417272L)        // 'rrAa'
        Bytes.putLe32(b, 488, 0xFFFFFFFFL)        // число свободных кластеров неизвестно
        Bytes.putLe32(b, 492, 2)                  // первый свободный кластер
        Bytes.putLe32(b, 508, 0xAA550000L)        // завершающая подпись
        return b
    }

    private fun buildFatFirstSector(): ByteArray {
        val b = ByteArray(blockSize)
        Bytes.putLe32(b, 0, 0x0FFFFFF8L) // дескриптор носителя + зарезервировано
        Bytes.putLe32(b, 4, 0x0FFFFFFFL) // EOC
        Bytes.putLe32(b, 8, 0x0FFFFFFFL) // кластер 2 (корень) — конец цепочки
        return b
    }

    private fun buildRootDir(): ByteArray {
        val b = ByteArray(clusterSize * blockSize)
        // Запись метки тома (атрибут 0x08); нулевой байт после неё закрывает каталог.
        Bytes.putAscii(b, 0, VOLUME_LABEL.padEnd(11), 11)
        b[11] = 0x08
        return b
    }

    // ------------------------------------------------------------ геометрия

    /** Таблица Microsoft: выбор размера кластера по объёму раздела. */
    private fun sectorsPerCluster(partSectors: Long): Int {
        val megabytes = partSectors * blockSize / (1024L * 1024L)
        return when {
            megabytes <= 64 -> 1
            megabytes <= 128 -> 2
            megabytes <= 256 -> 4
            megabytes <= 8192 -> 8
            megabytes <= 16384 -> 16
            megabytes <= 32768 -> 32
            else -> 64
        }
    }

    /** Подбор числа секторов FAT итерациями до сходимости. */
    private fun computeFatSize(partSectors: Long, clusterSize: Int): Long {
        var fat = 1L
        while (true) {
            val dataStart = RESERVED_SECTORS + FAT_COUNT * fat
            val clusters = (partSectors - dataStart) / clusterSize
            val needed = ((clusters + 2) * 4 + blockSize - 1) / blockSize
            if (needed <= fat) return fat
            fat++
            require(fat < partSectors / 4) { "Не удалось подобрать размер FAT" }
        }
    }

    private fun volumeId(): Long =
        (System.currentTimeMillis() and 0xFFFFFFFFL) xor (System.nanoTime() and 0xFFFFFFFFL)
}
