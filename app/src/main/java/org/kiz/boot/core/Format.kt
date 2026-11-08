package org.kiz.boot.core

/** Форматирование величин для UI и лога. */
object Format {

    fun fileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1024.0) "%.1f МБ".format(mb) else "%.2f ГБ".format(mb / 1024.0)
    }

    /** Скорость в МБ/с из байт в секунду. */
    fun speed(bytesPerSec: Double): String =
        "%.1f МБ/с".format(bytesPerSec / (1024.0 * 1024.0))

    /** «мм:сс» или «чч:мм:сс». */
    fun eta(seconds: Long): String {
        if (seconds < 0) return "—"
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    fun vidPid(vendorId: Int, productId: Int): String =
        "VID:%04X PID:%04X".format(vendorId, productId)
}
