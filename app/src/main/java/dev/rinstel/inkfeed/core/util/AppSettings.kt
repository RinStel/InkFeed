package dev.rinstel.inkfeed.core.util

import android.content.Context
import android.net.Uri
import dev.rinstel.inkfeed.core.model.ImagePolicy

class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("inkfeed_settings", Context.MODE_PRIVATE)

    var outputTreeUri: Uri?
        get() = preferences.getString("output_tree_uri", null)?.let(Uri::parse)
        set(value) = preferences.edit().putString("output_tree_uri", value?.toString()).apply()

    var cacheDays: Int
        get() = preferences.getInt("cache_days", 30)
        set(value) = preferences.edit().putInt("cache_days", value).apply()

    var imagePolicy: ImagePolicy
        get() = runCatching {
            ImagePolicy.valueOf(preferences.getString("image_policy", ImagePolicy.ESSENTIAL.name)!!)
        }.getOrDefault(ImagePolicy.ESSENTIAL)
        set(value) = preferences.edit().putString("image_policy", value.name).apply()

    var dailyLimit: Int
        get() = preferences.getInt("daily_limit", 30)
        set(value) = preferences.edit().putInt("daily_limit", value.coerceIn(1, 200)).apply()

    var htmlDebug: Boolean
        get() = preferences.getBoolean("html_debug", true)
        set(value) = preferences.edit().putBoolean("html_debug", value).apply()

    var downloadImages: Boolean
        get() = preferences.getBoolean("download_images", true)
        set(value) = preferences.edit().putBoolean("download_images", value).apply()

    var splitEpubBySource: Boolean
        get() = preferences.getBoolean("split_epub_by_source", false)
        set(value) = preferences.edit().putBoolean("split_epub_by_source", value).apply()

    var todayUnreadOnly: Boolean
        get() = preferences.getBoolean("today_unread_only", false)
        set(value) = preferences.edit().putBoolean("today_unread_only", value).apply()

    var lastDailyPath: String?
        get() = preferences.getString("last_daily_path", null)
        set(value) = preferences.edit().putString("last_daily_path", value).apply()

    var lastDailyGeneratedAt: Long
        get() = preferences.getLong("last_daily_generated_at", 0L)
        set(value) = preferences.edit().putLong("last_daily_generated_at", value).apply()

    var lastStarredPath: String?
        get() = preferences.getString("last_starred_path", null)
        set(value) = preferences.edit().putString("last_starred_path", value).apply()

    var lastCacheCleanupAt: Long
        get() = preferences.getLong("last_cache_cleanup_at", 0L)
        set(value) = preferences.edit().putLong("last_cache_cleanup_at", value).apply()

    var lastUpdateCheckAt: Long
        get() = preferences.getLong("last_update_check_at", 0L)
        set(value) = preferences.edit().putLong("last_update_check_at", value).apply()
}
