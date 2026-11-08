package org.kiz.boot.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.SystemClock
import org.kiz.boot.core.Bytes

/** Ошибка SCSI-транспорта: CHECK CONDITION или фазовая ошибка BBB. */
class ScsiException(
    message: String,
    val senseKey: Int = -1,
    val asc: Int = -1,
    val ascq: Int = -1
) : Exception(message)

/** Носитель перестал отвечать: обрыв кабеля, извлечение устройства, зависание контроллера. */
class UsbDisconnectedException(message: String) : Exception(message)

/** Расшифровка REQUEST SENSE. */
class SenseData(val key: Int, val asc: Int, val ascq: Int) {
    override fun toString(): String = "key=$key ASC=0x%02X ASCQ=0x%02X".format(asc, ascq)
}

/** Результат INQUIRY — строковая идентификация устройства. */
class InquiryData(val vendor: String, val product: String, val revision: String)

/**
 * Транспорт USB Mass Storage Bulk-Only (BBB): CBW (31 байт) → данные → CSW (13 байт).
 * Прямая запись «сырых» байт в bulk OUT эндпоинт физически не работает — накопитель
 * ожидает SCSI-команды, поэтому вся запись идёт через WRITE(10) этого класса.
 */
class ScsiTransport(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    private val interfaceNumber: Int
) {

    companion object {
        /** Секторов в одной SCSI-транзакции: 256 × 512 = 128 КиБ на команду. */
        const val SECTORS_PER_COMMAND = 256

        /** Размер блока до выполнения readCapacity(). */
        const val DEFAULT_BLOCK_SIZE = 512

        private const val CBW_SIGNATURE = 0x43425355L // 'USBC'
        private const val CSW_SIGNATURE = 0x53425355L // 'USBS'
        private const val CBW_SIZE = 31
        private const val CSW_SIZE = 13
        private const val MAX_TRANSFER_RETRIES = 3
        private const val BULK_TIMEOUT_MS = 10_000
        private const val SENSE_LENGTH = 18
        private const val MAX_SECTORS_PER_CSW = 65535

        /**
         * Порция одного bulkTransfer-вызова. Часть Android-устройств не принимает
         * одиночные usbfs-URB крупнее 16 КиБ (EMSGSIZE), поэтому фаза данных
         * SCSI-команды режется на такие порции — протоколу BBB разбиение
         * безразлично, важен только суммарный объём dCBWDataTransferLength.
         */
        private const val BULK_CHUNK_BYTES = 16 * 1024
    }

    private var tag = 0x4B495A42 // 'KIZB'

    /** Размер сектора носителя; уточняется после readCapacity(). */
    var blockSize: Int = DEFAULT_BLOCK_SIZE
        private set

    // ------------------------------------------------------------ низкий уровень

    /**
     * Надёжный bulk-обмен: добивает недокачанные фрагменты, снимает STALL
     * (CLEAR_FEATURE ENDPOINT_HALT) и ретраит при сбоях. Данные идут порциями
     * [BULK_CHUNK_BYTES] — см. пояснение к константе.
     */
    private fun bulk(endpoint: UsbEndpoint, buffer: ByteArray, offset: Int, length: Int): Int {
        var done = 0
        var failures = 0
        while (done < length) {
            val chunk = minOf(BULK_CHUNK_BYTES, length - done)
            val transferred = connection.bulkTransfer(
                endpoint, buffer, offset + done, chunk, BULK_TIMEOUT_MS
            )
            when {
                transferred < 0 -> {
                    failures++
                    if (failures > MAX_TRANSFER_RETRIES) {
                        throw UsbDisconnectedException(
                            "USB-передача не удалась после $failures попыток " +
                                "(не передано ${length - done} из $length байт)"
                        )
                    }
                    clearHalt(endpoint)
                    SystemClock.sleep(40L * failures)
                }
                transferred == 0 -> {
                    failures++
                    if (failures > MAX_TRANSFER_RETRIES) {
                        throw UsbDisconnectedException("USB-передача вернула 0 байт")
                    }
                    SystemClock.sleep(20L)
                }
                else -> done += transferred
            }
        }
        return done
    }

    /** CLEAR_FEATURE(ENDPOINT_HALT) — снятие состояния STALL на эндпоинте. */
    private fun clearHalt(endpoint: UsbEndpoint) {
        // bmRequestType: DIR_OUT | TYPE_STANDARD | RECIP_ENDPOINT (0x02, UsbConstants
        // констант USB_RECIP_* не экспортирует — значения из спецификации USB 2.0).
        val requestType = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_STANDARD or 0x02
        connection.controlTransfer(requestType, 0x01, 0x00, endpoint.address, null, 0, 1_000)
    }

    private fun sendCbw(expectedTag: Int, cb: ByteArray, dataLength: Int, dirIn: Boolean) {
        val cbw = ByteArray(CBW_SIZE)
        Bytes.putLe32(cbw, 0, CBW_SIGNATURE)
        Bytes.putLe32(cbw, 4, expectedTag)
        Bytes.putLe32(cbw, 8, dataLength.toLong())
        cbw[12] = if (dirIn) 0x80.toByte() else 0x00
        cbw[13] = 0x00 // LUN 0
        cbw[14] = cb.size.toByte()
        System.arraycopy(cb, 0, cbw, 15, cb.size)
        bulk(bulkOut, cbw, 0, CBW_SIZE)
    }

    private fun readCsw(expectedTag: Int): Int {
        val csw = ByteArray(CSW_SIZE)
        bulk(bulkIn, csw, 0, CSW_SIZE)
        val signature = Bytes.getLe32(csw, 0)
        if (signature != CSW_SIGNATURE) {
            throw ScsiException("Неверная сигнатура CSW: 0x%08X".format(signature))
        }
        val responseTag = Bytes.getLe32(csw, 4)
        if (responseTag != expectedTag.toLong()) {
            throw ScsiException("Неверный тег CSW: $responseTag (ожидался $expectedTag)")
        }
        return csw[12].toInt() and 0xFF
    }

    private fun execute(
        cb: ByteArray,
        data: ByteArray?,
        dirIn: Boolean,
        dataOffset: Int = 0,
        dataLength: Int = data?.size ?: 0
    ) {
        val expectedTag = ++tag
        sendCbw(expectedTag, cb, dataLength, dirIn)
        if (data != null && dataLength > 0) {
            if (dirIn) {
                bulk(bulkIn, data, dataOffset, dataLength)
            } else {
                bulk(bulkOut, data, dataOffset, dataLength)
            }
        }
        val status = readCsw(expectedTag)
        if (status == 0) return
        val sense = runCatching { requestSense() }.getOrNull()
        val details = sense?.toString() ?: "статус=$status"
        throw ScsiException(
            "SCSI-ошибка: $details",
            sense?.key ?: -1,
            sense?.asc ?: -1,
            sense?.ascq ?: -1
        )
    }

    private fun executeNoData(cb: ByteArray) = execute(cb, null, dirIn = false)

    // ------------------------------------------------------------ команды SCSI

    /** TEST UNIT READY — готовность носителя; CHECK CONDITION не пробрасывается. */
    fun testUnitReady(): Boolean {
        val expectedTag = ++tag
        return try {
            sendCbw(expectedTag, byteArrayOf(0x00, 0, 0, 0, 0, 0), 0, dirIn = false)
            readCsw(expectedTag) == 0
        } catch (e: ScsiException) {
            runCatching { requestSense() }
            false
        }
    }

    /** REQUEST SENSE — расшифровка статуса последней команды. */
    fun requestSense(): SenseData {
        val data = ByteArray(SENSE_LENGTH)
        val cb = ByteArray(6)
        cb[0] = 0x03
        cb[4] = SENSE_LENGTH.toByte()
        execute(cb, data, dirIn = true)
        return SenseData(
            key = data[2].toInt() and 0x0F,
            asc = data[12].toInt() and 0xFF,
            ascq = data[13].toInt() and 0xFF
        )
    }

    /** INQUIRY — идентификация производителя и модели. */
    fun inquiry(): InquiryData {
        val data = ByteArray(36)
        val cb = ByteArray(6)
        cb[0] = 0x12 // INQUIRY
        cb[4] = 36
        execute(cb, data, dirIn = true)
        return InquiryData(
            vendor = String(data, 8, 8, Charsets.US_ASCII).trim(),
            product = String(data, 16, 16, Charsets.US_ASCII).trim(),
            revision = String(data, 32, 4, Charsets.US_ASCII).trim()
        )
    }

    /**
     * READ CAPACITY(10) — последний LBA и размер блока.
     * @throws IllegalArgumentException носитель больше 2 ТиБ (нужен READ CAPACITY(16))
     */
    fun readCapacity(): Pair<Long, Int> {
        val data = ByteArray(8)
        val cb = ByteArray(10)
        cb[0] = 0x25 // READ CAPACITY(10)
        execute(cb, data, dirIn = true)
        val lastLba = Bytes.getBe32(data, 0)
        val block = Bytes.getBe32(data, 4).toInt()
        require(lastLba != 0xFFFFFFFFL) { "Носитель больше 2 ТиБ не поддерживается" }
        require(block in 512..65536 && (block and (block - 1)) == 0) {
            "Некорректный размер блока: $block"
        }
        blockSize = block
        return lastLba to block
    }

    /** WRITE(10) — запись [sectors] секторов начиная с LBA [lba] из [data] с позиции [offset]. */
    fun write10(lba: Long, sectors: Int, data: ByteArray, offset: Int = 0) {
        check(lba >= 0 && sectors > 0)
        check(sectors <= MAX_SECTORS_PER_CSW) { "Слишком длинная команда: $sectors секторов" }
        check(data.size >= offset + sectors * blockSize) { "Буфер меньше запрошенной записи" }
        val cb = ByteArray(10)
        cb[0] = 0x2A // WRITE(10)
        Bytes.putBe32(cb, 2, lba)
        Bytes.putBe16(cb, 7, sectors)
        execute(cb, data, dirIn = false, dataOffset = offset, dataLength = sectors * blockSize)
    }

    /** READ(10) — чтение [sectors] секторов начиная с LBA [lba] в [data] с позиции [offset]. */
    fun read10(lba: Long, sectors: Int, data: ByteArray, offset: Int = 0) {
        check(lba >= 0 && sectors > 0)
        check(sectors <= MAX_SECTORS_PER_CSW) { "Слишком длинная команда: $sectors секторов" }
        check(data.size >= offset + sectors * blockSize) { "Буфер меньше запрошенного чтения" }
        val cb = ByteArray(10)
        cb[0] = 0x28 // READ(10)
        Bytes.putBe32(cb, 2, lba)
        Bytes.putBe16(cb, 7, sectors)
        execute(cb, data, dirIn = true, dataOffset = offset, dataLength = sectors * blockSize)
    }

    /** SYNCHRONIZE CACHE(10) — порция не длиннее 65535 секторов; разбивку делает BootEngine. */
    fun synchronizeCache10(lba: Long, sectors: Int) {
        check(lba >= 0 && sectors > 0 && sectors <= MAX_SECTORS_PER_CSW)
        val cb = ByteArray(10)
        cb[0] = 0x35
        Bytes.putBe32(cb, 2, lba)
        Bytes.putBe16(cb, 7, sectors)
        executeNoData(cb)
    }

    /**
     * BULK ONLY MASS STORAGE RESET — аварийный сброс устройства class-запросом
     * (RECIP_INTERFACE = 0x01) с последующей очисткой STALL на обоих эндпоинтах.
     */
    fun massStorageReset() {
        val requestType = UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_CLASS or 0x01
        connection.controlTransfer(requestType, 0xFF, 0x00, interfaceNumber, null, 0, 5_000)
        clearHalt(bulkIn)
        clearHalt(bulkOut)
    }
}
