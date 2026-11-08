package org.kiz.boot.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Шина состояния между BootWriterService (продюсер) и UI (наблюдатель).
 * Синглтон осознанно: состояние операции должно переживать повороты экрана
 * и пересоздание Activity, а жизненный цикл совпадает с процессом приложения.
 */
object WriterBus {

    private const val MAX_LOG_LINES = 40

    private val _state = MutableStateFlow(WriterState())
    val state: StateFlow<WriterState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    fun set(newState: WriterState) {
        _state.value = newState
    }

    fun update(block: (WriterState) -> WriterState) {
        _state.value = block(_state.value)
    }

    /** Добавляет строку в кольцевой лог операции (последние [MAX_LOG_LINES] строк). */
    fun log(line: String) {
        _log.value = (_log.value + "· $line").takeLast(MAX_LOG_LINES)
    }

    fun clearLog() {
        _log.value = emptyList()
    }
}
