package com.alban.ebike.terrain

import java.io.*
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Versioned derived geometry; never includes the user's GPS track. All calls on worker threads. */
class GeometryCache(private val directory: File) {
    @Volatile var enabled = true
    @Synchronized fun clearMemory() { memory.clear() }
    private val memory = LinkedHashMap<String, List<FloatArray>>(4, .75f, true)
    @Synchronized fun read(key: String): Pair<List<FloatArray>, String>? {
        if (!enabled) return null
        memory[key]?.let { return it to "RAM" }
        val file = File(directory, "$key.bin")
        return runCatching {
            require(file.length() in 12..MAX_ENTRY)
            val arrays = DataInputStream(file.inputStream().buffered()).use { input ->
                require(input.readInt() == MAGIC)
                val count = input.readInt(); require(count in 1..4)
                var remaining = file.length() - 8
                List(count) {
                    val size = input.readInt(); remaining -= 4
                    require(size >= 0 && size % 3 == 0 && size.toLong() * 4 <= remaining)
                    remaining -= size.toLong() * 4
                    FloatArray(size) { input.readFloat().also { require(it.isFinite()) } }
                }.also { require(remaining == 0L) }
            }
            remember(key, arrays); file.setLastModified(System.currentTimeMillis())
            arrays to "DISQUE"
        }.getOrNull()
    }
    @Synchronized fun write(key: String, arrays: List<FloatArray>) {
        if (!enabled) return
        remember(key, arrays)
        if (arrays.sumOf { it.size.toLong() * 4 } > MAX_ENTRY - 24) return
        runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val target = File(directory, "$key.bin")
            val temp = File.createTempFile("geometry-", ".part", directory)
            try {
                DataOutputStream(temp.outputStream().buffered()).use { output ->
                    output.writeInt(MAGIC); output.writeInt(arrays.size)
                    arrays.forEach { array -> output.writeInt(array.size); array.forEach(output::writeFloat) }
                }
                check(temp.renameTo(target))
            } finally { temp.delete() }
            val files = directory.listFiles { f -> f.extension == "bin" }?.sortedBy { it.lastModified() }.orEmpty()
            var bytes = files.sumOf { it.length() }
            for (file in files) {
                if (bytes <= 128L * 1024 * 1024) break
                val length = file.length(); if (file.delete()) bytes -= length
            }
        }
    }
    private fun remember(key: String, arrays: List<FloatArray>) {
        memory[key] = arrays
        while (memory.size > 4 || (memory.size > 1 && memory.values.sumOf { arrays -> arrays.sumOf { it.size.toLong() * 4 } } > 32L * 1024 * 1024)) {
            memory.remove(memory.keys.first())
        }
    }
    companion object {
        private const val MAGIC = 0x45424731
        private const val MAX_ENTRY = 48L * 1024 * 1024
        fun key(terrain: TerrainGrid, detail: TerrainDetail, area: MapFeatureArea? = null): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val sink = object : OutputStream() { override fun write(b: Int) {} ; override fun write(b: ByteArray, off: Int, len: Int) {} }
            DataOutputStream(BufferedOutputStream(DigestOutputStream(sink, digest))).use { out ->
                // Bump when triangulation, projection, smoothing or exaggeration changes.
                out.writeUTF("geometry-v2-all-layers-exaggeration-1.8"); out.writeUTF(detail.name)
                out.writeDouble(terrain.originLat); out.writeDouble(terrain.originLon)
                out.writeDouble(terrain.halfSizeM); out.writeInt(terrain.size); out.writeBoolean(terrain.real)
                terrain.heights.forEach(out::writeFloat)
                out.writeBoolean(area != null)
                area?.features?.forEach { feature ->
                    out.writeBoolean(feature.water); out.writeBoolean(feature.major)
                    out.writeBoolean(feature.path); out.writeBoolean(feature.building)
                    out.writeInt(feature.points.size)
                    feature.points.forEach { out.writeDouble(it.latitude); out.writeDouble(it.longitude) }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
