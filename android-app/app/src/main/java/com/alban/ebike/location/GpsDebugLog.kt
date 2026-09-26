package com.alban.ebike.location

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Private, bounded diagnostic journal. No latitude/longitude is recorded. */
object GpsDebugLog {
    @Volatile private var directory: File? = null
    private val writer = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(256), ThreadPoolExecutor.DiscardOldestPolicy())

    fun initialize(context: Context) {
        directory = File(context.applicationContext.filesDir, "gps-logs")
    }

    fun record(message: String) {
        val folder = directory ?: return
        val line = "${System.currentTimeMillis()}\t${message.replace('\n', ' ')}\n"
        writer.execute {
            runCatching {
                check(folder.isDirectory || folder.mkdirs())
                val current = File(folder, "gps-current.log")
                if (current.length() >= 4 * 1024 * 1024) {
                    val oldest = File(folder, "gps-2.log")
                    check(!oldest.exists() || oldest.delete())
                    val previous = File(folder, "gps-1.log")
                    check(!previous.exists() || previous.renameTo(oldest))
                    check(current.renameTo(previous))
                }
                current.appendText(line, Charsets.UTF_8)
            }.onFailure { Log.e("EBikeGPS", "GPS journal write failed", it) }
        }
    }
}
