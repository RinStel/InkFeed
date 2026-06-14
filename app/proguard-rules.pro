# InkFeed R8 / ProGuard 优化规则
# 基于 proguard-android-optimize.txt，追加更激进的优化选项

# ── 保留项（必须保留的类） ──

# Application / Activity（Manifest 引用）
-keep class dev.rinstel.inkfeed.InkFeedApplication { *; }
-keep class dev.rinstel.inkfeed.MainActivity { *; }

# 数据模型（Cursor 映射通过名称访问构造函数和属性）
-keep class dev.rinstel.inkfeed.core.model.** { *; }

# 数据库（SQLiteOpenHelper 子类）
-keep class dev.rinstel.inkfeed.core.database.InkFeedDatabase { *; }

# FeedParser 入口（外部可能通过类名调用）
-keep class dev.rinstel.inkfeed.feed.parser.FeedParser { public *; }

# CrashReporter（反射调用）
-keep class dev.rinstel.inkfeed.CrashReporter { *; }

# ── 依赖库保留 ──

# Jsoup — 保留公共 API
-keep class org.jsoup.** { *; }

# OkHttp — 保留公共 API
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# ── 移除项（安全移除的类） ──

# 移除 Kotlin 反射相关（应用未使用 kotlin-reflect）
-dontwarn kotlin.reflect.**
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
}

# ── 优化选项 ──

# 合并类和接口（更激进的内联）
-allowaccessmodification
-mergeinterfacesaggressively

# 优化次数（默认 1 次，增加可略微减小体积）
-optimizationpasses 5

# 移除调试和日志（Release 中不需要）
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
