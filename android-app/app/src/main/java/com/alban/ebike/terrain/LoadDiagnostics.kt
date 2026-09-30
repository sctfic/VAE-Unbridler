package com.alban.ebike.terrain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LoadDiagnostics {
    data class Step(val message: String, val start: Long, val end: Long? = null)
    private val mutable = MutableStateFlow<Map<String, Step>>(emptyMap())
    val steps = mutable.asStateFlow()
    fun begin(lane: String, message: String) { mutable.update { it + (lane to Step(message, now())) } }
    fun report(lane: String, message: String) { mutable.update { old ->
        old + (lane to (old[lane]?.copy(message = message) ?: Step(message, now())))
    } }
    fun end(lane: String) { mutable.update { old ->
        old[lane]?.let { old + (lane to it.copy(end = it.end ?: now())) } ?: old
    } }
    companion object { fun now() = System.nanoTime() / 1_000_000 }
}
