package org.kiz.boot.core

/**
 * Байтовые утилиты для построения структур SCSI/MBR/FAT.
 * Все методы работают с беззнаковыми значениями через widening до Long.
 */
object Bytes {

    fun putLe16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    fun putLe32(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 4) dst[off + i] = ((v shr (8 * i)) and 0xFFL).toByte()
    }

    fun putLe32(dst: ByteArray, off: Int, v: Int) = putLe32(dst, off, v.toLong() and 0xFFFFFFFFL)

    fun putBe16(dst: ByteArray, off: Int, v: Int) {
        dst[off] = ((v shr 8) and 0xFF).toByte()
        dst[off + 1] = (v and 0xFF).toByte()
    }

    fun putBe32(dst: ByteArray, off: Int, v: Long) {
        for (i in 0 until 4) dst[off + i] = ((v shr (8 * (3 - i))) and 0xFFL).toByte()
    }

    fun putBe32(dst: ByteArray, off: Int, v: Int) = putBe32(dst, off, v.toLong() and 0xFFFFFFFFL)

    fun getLe32(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = v or ((src[off + i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    fun getBe32(src: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (src[off + i].toLong() and 0xFF)
        return v
    }

    /** Пишет ASCII-строку фиксированной длины, добивая недостающее пробелами. */
    fun putAscii(dst: ByteArray, off: Int, text: String, len: Int) {
        for (i in 0 until len) {
            dst[off + i] = if (i < text.length) text[i].code.toByte() else 0x20.toByte()
        }
    }
}
