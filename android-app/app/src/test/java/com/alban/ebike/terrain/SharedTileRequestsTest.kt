package com.alban.ebike.terrain

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SharedTileRequestsTest {
    @Test fun cancellationDrainsWritersAndAllowsLaterDownloads() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val requests = SharedTileRequests<String, Int>(owner) { key ->
            if (key == "slow") {
                try { started.complete(Unit); awaitCancellation() }
                finally { finished.complete(Unit) }
            } else 42
        }
        try {
            val waiter = launch { requests.get("slow") }
            withTimeout(3000) { started.await(); requests.cancelAll(); finished.await(); waiter.join() }
            assertTrue(waiter.isCancelled)
            assertEquals(42, requests.get("next"))
        } finally { owner.cancel() }
    }
}
