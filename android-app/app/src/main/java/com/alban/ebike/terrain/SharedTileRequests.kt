package com.alban.ebike.terrain

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The owner's scope, not the awaiting screen, owns the download and persistence. */
class SharedTileRequests<K, V>(private val scope: CoroutineScope, private val fetch: suspend (K) -> V) {
    private val mutex = Mutex()
    private val pending = HashMap<K, Deferred<V>>()
    suspend fun get(key: K): V {
        val request = mutex.withLock {
            pending.entries.removeAll { it.value.isCompleted }
            pending.getOrPut(key) { scope.async(Dispatchers.IO) { fetch(key) } }
        }
        return request.await()
    }
}
