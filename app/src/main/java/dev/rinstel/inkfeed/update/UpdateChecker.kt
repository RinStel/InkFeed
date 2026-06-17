package dev.rinstel.inkfeed.update

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val title: String,
    val pageUrl: String,
    val downloadUrl: String,
    val publishedAt: String?,
    val notes: String?
)

class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    fun check(currentVersion: String): UpdateInfo? {
        val request = Request.Builder()
            .url(LATEST_RELEASE_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "InkFeed")
            .build()
        val body = client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) error("GitHub 更新检查失败：HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val release = JSONObject(body)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val remoteVersion = normalizeVersion(
            release.optString("tag_name").ifBlank { release.optString("name") }
        )
        if (!isNewer(remoteVersion, normalizeVersion(currentVersion))) return null
        val pageUrl = release.optString("html_url")
        val assets = release.optJSONArray("assets")
        var downloadUrl = pageUrl
        if (assets != null) {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (name.endsWith(".apk", ignoreCase = true) && url.isNotBlank()) {
                    downloadUrl = url
                    break
                }
            }
        }
        return UpdateInfo(
            version = remoteVersion,
            title = release.optString("name").ifBlank { "InkFeed $remoteVersion" },
            pageUrl = pageUrl,
            downloadUrl = downloadUrl,
            publishedAt = release.optString("published_at").ifBlank { null },
            notes = release.optString("body").ifBlank { null }
        )
    }

    companion object {
        private const val LATEST_RELEASE_API =
            "https://api.github.com/repos/RinStel/InkFeed/releases/latest"

        internal fun normalizeVersion(value: String): String =
            value.trim()
                .removePrefix("refs/tags/")
                .removePrefix("release-")
                .removePrefix("v")
                .removePrefix("V")

        internal fun isNewer(remote: String, current: String): Boolean {
            val remoteParts = versionParts(remote)
            val currentParts = versionParts(current)
            val count = maxOf(remoteParts.size, currentParts.size)
            for (index in 0 until count) {
                val left = remoteParts.getOrElse(index) { 0 }
                val right = currentParts.getOrElse(index) { 0 }
                if (left != right) return left > right
            }
            return false
        }

        private fun versionParts(value: String): List<Int> =
            value.split('.', '-', '_')
                .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }
                .ifEmpty { listOf(0) }
    }
}
