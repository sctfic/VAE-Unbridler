package com.alban.ebike.firmware

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Public GitHub releases only; no token or account is stored in the application. */
class GithubFirmwareRepository {
    fun latest(): FirmwareRelease? {
        val releases = JSONArray(readText("https://api.github.com/repos/$REPO/releases?per_page=100", 2_000_000))
        val candidates = (0 until releases.length()).map { i ->
            val r = releases.getJSONObject(i)
            GithubReleaseCandidate(i, r.getString("tag_name"), r.optBoolean("draft"),
                r.optBoolean("prerelease"), r.optString("published_at"))
        }
        for (candidate in firmwareReleaseCandidates(candidates)) {
            val i = candidate.index
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val tag = release.getString("tag_name")
            if (!tag.matches(Regex("esp32-hw-v1-v[0-9]+\\.[0-9]+\\.[0-9]+"))) continue
            val assets = release.getJSONArray("assets")
            val names = (0 until assets.length()).associate { index ->
                val a = assets.getJSONObject(index)
                a.getString("name") to a.getString("browser_download_url")
            }
            val prefix = "https://github.com/$REPO/releases/download/$tag/"
            val manifestUrl = names[MANIFEST] ?: continue
            require(manifestUrl == prefix + MANIFEST) { "Adresse du manifeste GitHub inattendue." }
            val json = JSONObject(readText(manifestUrl, 16_384))
            require(json.getInt("schema") == 1 && json.getString("target") == "ebike-esp32s3-hw-v1" &&
                json.getInt("ota_protocol") == 1 && json.getString("hardware_revision") == "V1.0") { "Firmware incompatible avec cette carte." }
            val result = FirmwareRelease(json.getString("version"), json.getInt("version_code"),
                json.getInt("size"), json.getString("sha256"), json.getString("download_url"))
            require(tag == "esp32-hw-v1-v${result.version}" && result.versionCode > 0 && result.size in 1..MAX_SIZE &&
                result.sha256.matches(Regex("[a-f0-9]{64}")) && result.url == prefix + BINARY &&
                names[BINARY] == result.url) { "Manifeste OTA invalide." }
            return result
        }
        return null
    }

    fun download(release: FirmwareRelease, file: File, progress: (Int) -> Unit) {
        val connection = connect(release.url)
        try {
            val length = connection.contentLengthLong
            require(length == -1L || length == release.size.toLong()) { "Taille du téléchargement inattendue." }
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0
            connection.inputStream.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= release.size) { "Firmware trop volumineux." }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                    progress(total)
                }
            } }
            require(total == release.size) { "Téléchargement incomplet." }
            require(digest.digest().joinToString("") { "%02x".format(it) } == release.sha256) {
                "Contrôle SHA-256 incorrect : téléchargement rejeté."
            }
        } finally { connection.disconnect() }
    }

    private fun readText(url: String, max: Int): String {
        val connection = connect(url)
        try {
            return connection.inputStream.use {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= max) { "Réponse GitHub trop volumineuse." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }

    private fun connect(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            require(current.protocol == "https" && current.userInfo == null &&
                current.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                "Adresse de téléchargement non autorisée."
            }
            val connection = (current.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "E-BikeCockpit-OTA")
                setRequestProperty("Accept", "application/octet-stream, application/json")
            }
            val status = connection.responseCode
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                require(!location.isNullOrBlank()) { "Redirection GitHub invalide." }
                current = URL(current, location)
            } else {
                if (status != 200) { connection.disconnect(); error("GitHub indisponible (HTTP $status). Réessayez plus tard.") }
                return connection
            }
        }
        error("Trop de redirections GitHub.")
    }

    companion object {
        const val REPO = "sctfic/VAE-Unbridler"
        const val MANIFEST = "ebike-esp32s3-hw-v1-ota.json"
        const val BINARY = "ebike-esp32s3-hw-v1.bin"
        const val MAX_SIZE = 0x1e0000
    }
}
