package dev.rinstel.inkfeed

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.rinstel.inkfeed.article.cache.CacheCleaner
import dev.rinstel.inkfeed.core.database.InkFeedDatabase
import dev.rinstel.inkfeed.core.model.Article
import dev.rinstel.inkfeed.core.model.ImagePolicy
import dev.rinstel.inkfeed.core.model.Source
import dev.rinstel.inkfeed.core.util.AppSettings
import dev.rinstel.inkfeed.core.util.BeijingTime
import dev.rinstel.inkfeed.epub.builder.BuildResult
import dev.rinstel.inkfeed.epub.builder.EpubBuilder
import dev.rinstel.inkfeed.feed.opml.OpmlImporter
import dev.rinstel.inkfeed.feed.sync.FeedSyncService
import dev.rinstel.inkfeed.feed.sync.SyncResult
import dev.rinstel.inkfeed.update.UpdateChecker
import dev.rinstel.inkfeed.update.UpdateInfo
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var database: InkFeedDatabase
    private lateinit var settings: AppSettings
    private lateinit var syncService: FeedSyncService
    private lateinit var epubBuilder: EpubBuilder
    private lateinit var cacheCleaner: CacheCleaner
    private lateinit var updateChecker: UpdateChecker
    private lateinit var content: FrameLayout
    private lateinit var status: TextView
    private lateinit var pageIndicator: TextView
    private var pageScrollView: PagingScrollView? = null
    private val pagingTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop * 3 }
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var pagingGestureConsumed = false
    private var pendingPageDirection = 0
    private val executor = Executors.newSingleThreadExecutor()
    private val navigationButtons = mutableMapOf<Page, Button>()
    private var page = Page.TODAY

    private val outputDirectoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        settings.outputTreeUri = uri
        showSettings()
    }

    private val opmlLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        runTask("正在导入 OPML…", {
            contentResolver.openInputStream(uri)!!.use { OpmlImporter.import(it, database) }
        }) { count ->
            status.text = "已导入 $count 个订阅源"
            showSources()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var stage = "window"
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            stage = "database"
            database = InkFeedDatabase(this)
            database.readableDatabase
            stage = "settings"
            settings = AppSettings(this)
            stage = "services"
            syncService = FeedSyncService(this, database, settings)
            epubBuilder = EpubBuilder(this, settings, database)
            cacheCleaner = CacheCleaner(database)
            updateChecker = UpdateChecker()
            stage = "layout"
            setContentView(buildRoot())
            stage = "today"
            showToday()
            scheduleCacheCleanupIfNeeded()
            checkForUpdatesIfNeeded()
        } catch (error: Throwable) {
            val path = CrashReporter.write(this, stage, error)
            showStartupError(stage, path, error)
        }
    }

    override fun onDestroy() {
        if (::syncService.isInitialized) syncService.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun scheduleCacheCleanupIfNeeded() {
        val lastCleanup = settings.lastCacheCleanupAt
        val dayStart = BeijingTime.startOfDayMillis()
        if (lastCleanup >= dayStart) return
        executor.execute {
            runCatching { cacheCleaner.clean(settings.cacheDays) }
                .onSuccess { settings.lastCacheCleanupAt = System.currentTimeMillis() }
                .onFailure { Log.e(TAG, "Unable to clean cache", it) }
        }
    }

    private fun showStartupError(stage: String, path: String, error: Throwable) {
        val details = CrashReporter.stackTrace(error)
            .lineSequence()
            .take(18)
            .joinToString("\n")
        val message = """
            InkFeed 启动失败

            阶段：$stage
            系统：Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）
            设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}
            错误：${error.javaClass.simpleName}: ${error.message.orEmpty()}

            日志文件：
            $path

            请拍摄此页面，或在文件管理器中找到 crash-latest.txt。

            $details
        """.trimIndent()
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(TextView(this@MainActivity).apply {
                text = message
                textSize = 14f
                setTextColor(Color.BLACK)
                setTextIsSelectable(true)
                setPadding(dp(20), dp(20), dp(20), dp(20))
            })
        })
    }

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        status = TextView(this).apply {
            text = "就绪"
            setTextColor(Color.DKGRAY)
            setPadding(dp(16), 0, dp(16), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        statusBar.addView(status, LinearLayout.LayoutParams(0, dp(30), 1f))
        pageIndicator = TextView(this).apply {
            text = "1 / 1"
            gravity = Gravity.CENTER
            setTextColor(Color.DKGRAY)
        }
        statusBar.addView(pageIndicator, LinearLayout.LayoutParams(dp(64), dp(30)))
        root.addView(statusBar)
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        listOf(
            "今日" to Page.TODAY,
            "订阅源" to Page.SOURCES,
            "收藏" to Page.STARRED,
            "设置" to Page.SETTINGS
        ).forEach { (label, target) ->
            val button = outlineButton(label) {
                page = target
                refreshPage()
            }.apply {
                background = buttonBackground(selected = page == target)
            }
            navigationButtons[target] = button
            navigation.addView(button, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                setMargins(dp(3), 0, dp(3), dp(3))
            })
        }
        root.addView(navigation)
        return root
    }

    private fun showToday() {
        page = Page.TODAY
        val articles = todayArticles()
        val visibleArticles = articlesForTodayFilter(articles)
        val unreadCount = articles.count { !it.isRead }
        Log.d(TAG, "showToday: articles=${articles.size} synced=${articles.map { it.syncedAt ?: 0L }}")
        val sources = database.sources()
        val root = pageLayout()
        root.addView(sectionTitle("今日阅读包"))
        val lastSync = sources.mapNotNull { it.lastSyncAt }.maxOrNull()
        root.addView(bodyText(
            "今日文章：${articles.size} 篇\n" +
                "未读文章：$unreadCount 篇\n" +
                "当前列表：${visibleArticles.size} 篇 · ${visibleArticles.sumOf { it.readingMinutes }} 分钟\n" +
                "最近同步：${formatTime(lastSync)}\n" +
                "阅读包：${dailyPackageStatus()}\n" +
                "输出目录：${outputPath()}"
        ))
        root.addView(outlineButton(
            if (settings.todayUnreadOnly) "同步并生成未读 EPUB" else "同步并生成今日 EPUB"
        ) { syncAndBuildDaily() }, fullWidthButtonParams(8))
        root.addView(buttonRow(
            outlineButton(if (settings.todayUnreadOnly) "显示全部" else "只看未读") {
                settings.todayUnreadOnly = !settings.todayUnreadOnly
                showToday()
            },
            outlineButton("全部已读") { markTodayRead(visibleArticles) },
            outlineButton("打开目录") { openOutputDirectory() }
        ))
        root.addView(sectionTitle("文章"))
        if (articles.isEmpty()) {
            root.addView(emptyText("尚无文章。请先在“订阅源”添加 RSS / Atom 并同步。"))
        } else if (visibleArticles.isEmpty()) {
            root.addView(emptyText("当前筛选下没有文章。"))
        } else {
            visibleArticles.forEach { root.addView(articleView(it, allowUnstar = false)) }
        }
        show(root)
    }

    private fun showSources() {
        page = Page.SOURCES
        val root = pageLayout()
        root.addView(buttonRow(
            outlineButton("添加 RSS / Atom") { showAddSourceDialog() },
            outlineButton("导入 OPML") {
                opmlLauncher.launch(arrayOf("text/xml", "application/xml", "text/x-opml"))
            }
        ))
        root.addView(sectionTitle("订阅源"))
        val sources = database.sources()
        if (sources.isEmpty()) root.addView(emptyText("尚未添加订阅源。"))
        sources.forEach { root.addView(sourceView(it)) }
        show(root)
    }

    private fun showStarred() {
        page = Page.STARRED
        val articles = database.articles(starredOnly = true)
        val root = pageLayout()
        root.addView(sectionTitle("收藏阅读包"))
        root.addView(bodyText("路径：${settings.lastStarredPath ?: "尚未生成"}"))
        root.addView(outlineButton("生成收藏 EPUB") {
            if (articles.isEmpty()) status.text = "没有收藏文章"
            else runTask("正在生成收藏 EPUB…", { epubBuilder.buildStarred(articles) }) {
                settings.lastStarredPath = it.displayPath
                status.text = "已生成 ${it.displayPath}（${it.articleCount} 篇）"
                showStarred()
            }
        })
        root.addView(sectionTitle("收藏文章"))
        if (articles.isEmpty()) root.addView(emptyText("尚无收藏文章。"))
        articles.forEach { root.addView(articleView(it, allowUnstar = true)) }
        show(root)
    }

    private fun showSettings() {
        page = Page.SETTINGS
        val root = pageLayout()
        root.addView(sectionTitle("输出目录"))
        root.addView(bodyText(outputPath()))
        root.addView(outlineButton("选择输出目录") {
            outputDirectoryLauncher.launch(settings.outputTreeUri)
        }, fullWidthButtonParams(bottomMargin = 8))
        root.addView(
            outlineButton("打开输出目录") { openOutputDirectory() },
            fullWidthButtonParams()
        )

        root.addView(sectionTitle("缓存天数"))
        root.addView(choiceSpinner(
            listOf("7", "14", "30", "90"),
            settings.cacheDays.toString()
        ) { settings.cacheDays = it.toInt() })

        root.addView(sectionTitle("图片策略"))
        root.addView(choiceSpinner(
            ImagePolicy.entries.map { it.label },
            settings.imagePolicy.label
        ) { label -> settings.imagePolicy = ImagePolicy.entries.first { it.label == label } })

        root.addView(sectionTitle("每日最大文章数"))
        val limit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(settings.dailyLimit.toString())
            setTextColor(Color.BLACK)
            setHintTextColor(Color.DKGRAY)
            background = inputBackground()
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(limit)
        root.addView(outlineButton("保存文章数") {
            settings.dailyLimit = limit.text.toString().toIntOrNull() ?: 30
            status.text = "设置已保存"
        })

        root.addView(settingCheck("生成 HTML 调试文件", settings.htmlDebug) {
            settings.htmlDebug = it
        })
        root.addView(settingCheck("同步时下载图片", settings.downloadImages) {
            settings.downloadImages = it
        })
        root.addView(settingCheck("按来源额外生成 EPUB", settings.splitEpubBySource) {
            settings.splitEpubBySource = it
        })
        root.addView(outlineButton("立即清理过期缓存") {
            runTask("正在清理缓存…", { cacheCleaner.clean(settings.cacheDays) }) {
                status.text = "已清理 ${it.articleCount} 篇文章和 ${it.assetCount} 个图片文件"
            }
        })
        root.addView(sectionTitle("应用更新"))
        root.addView(bodyText("当前版本：${currentVersionName()}"))
        root.addView(outlineButton("检查更新") {
            checkForUpdates(manual = true)
        })
        root.addView(sectionTitle("KOReader 协作"))
        root.addView(bodyText(
            "InkFeed 将 EPUB 写入所选目录的 daily/ 和 starred/ 子目录。" +
                "请在 KOReader 文件浏览器中打开生成的 EPUB。"
        ))
        show(root)
    }

    private fun showAddSourceDialog() {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }
        val title = EditText(this).apply {
            hint = "名称（可留空）"
            setTextColor(Color.BLACK)
            setHintTextColor(Color.DKGRAY)
            background = inputBackground()
        }
        val url = EditText(this).apply {
            hint = "https://example.com/feed.xml"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.BLACK)
            setHintTextColor(Color.DKGRAY)
            background = inputBackground()
        }
        form.addView(title)
        form.addView(url)
        AlertDialog.Builder(this)
            .setTitle("添加 RSS / Atom")
            .setView(form)
            .setPositiveButton("添加") { _, _ ->
                val feedUrl = url.text.toString().trim()
                if (!feedUrl.startsWith("http://") && !feedUrl.startsWith("https://")) {
                    status.text = "请输入有效的 HTTP(S) 地址"
                } else {
                    val name = title.text.toString().trim().ifBlank { feedUrl }
                    val id = database.addSource(name, feedUrl)
                    status.text = if (id == -1L) "该订阅源已存在" else "订阅源已添加"
                    showSources()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sourceView(source: Source): View {
        val box = itemBox()
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = source.title
            textSize = 17f
            setTextColor(Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, dp(12), 0)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        var enabledState = source.enabled
        val enabled = outlineButton(if (enabledState) "已启用" else "已停用") {
            enabledState = !enabledState
            database.setSourceEnabled(source.id, enabledState)
        }.apply {
            minWidth = 0
            minHeight = 0
            textSize = 14f
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                enabledState = !enabledState
                database.setSourceEnabled(source.id, enabledState)
                text = if (enabledState) "已启用" else "已停用"
                background = buttonBackground(selected = enabledState)
                status.text = if (enabledState) "已启用 ${source.title}" else "已停用 ${source.title}"
            }
            background = buttonBackground(selected = enabledState)
        }
        header.addView(enabled, LinearLayout.LayoutParams(dp(88), dp(40)).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        box.addView(header, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        box.addView(bodyText(
            "${source.feedUrl}\n最近同步：${formatTime(source.lastSyncAt)}\n" +
                "结果：${source.lastSyncResult ?: "未同步"}"
        ))
        box.addView(buttonRow(
            outlineButton("同步") {
                runTask("正在同步 ${source.title}…", { syncService.sync(source) }) {
                    status.text = "同步完成：新增 ${it.added}，重复 ${it.duplicate}，失败 ${it.failed}"
                    refreshPage()
                }
            },
            outlineButton("查看文章") { showSourceArticles(source) },
            outlineButton("删除") {
                AlertDialog.Builder(this)
                    .setMessage("删除“${source.title}”及其文章？")
                    .setPositiveButton("删除") { _, _ ->
                        database.deleteSource(source.id).forEach { path ->
                            runCatching {
                                val file = java.io.File(path)
                                file.delete()
                                file.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
                            }
                        }
                        showSources()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        ))
        return box
    }

    private fun showSourceArticles(source: Source) {
        val articles = database.articles(sourceId = source.id)
        val root = pageLayout()
        root.addView(outlineButton("返回订阅源") { showSources() })
        root.addView(sectionTitle(source.title))
        root.addView(bodyText("${source.feedUrl}\n共 ${articles.size} 篇本地文章"))
        if (articles.isEmpty()) root.addView(emptyText("该订阅源尚无本地文章。"))
        articles.forEach { root.addView(articleView(it, allowUnstar = false)) }
        show(root)
    }

    private fun articleView(article: Article, allowUnstar: Boolean): View {
        val box = itemBox()
        box.addView(TextView(this).apply {
            text = "[${article.sourceTitle}]"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        })
        box.addView(TextView(this).apply {
            text = article.title
            textSize = 17f
            setTextColor(Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(4))
        })
        article.summary?.takeIf { it.isNotBlank() }?.let {
            box.addView(bodyText(it.take(180)))
        }
        box.addView(bodyText(
            "${formatTime(article.publishedAt)} · 预计阅读 ${article.readingMinutes} 分钟 · " +
                if (article.isRead) "已读" else "未读"
        ))
        box.addView(buttonRow(
            outlineButton(if (article.isRead) "标为未读" else "标为已读") {
                database.setRead(article.id, !article.isRead)
                status.text = if (article.isRead) "已标为未读" else "已标为已读"
                refreshPage()
            },
            outlineButton(if (allowUnstar || article.isStarred) "取消收藏" else "收藏") {
                database.setStarred(article.id, !article.isStarred)
                status.text = if (article.isStarred) "已取消收藏" else "已收藏"
                refreshPage()
            }
        ))
        return box
    }

    private fun syncAndBuildDaily() {
        runTask("正在同步并生成今日 EPUB…", {
            val syncResult = syncService.syncAll()
            val articles = articlesForTodayFilter(todayArticles())
            if (articles.isEmpty()) {
                SyncAndBuildResult(syncResult, null)
            } else {
                val buildResult = epubBuilder.buildDaily(articles)
                database.markPackaged(articles.map(Article::id))
                settings.lastDailyPath = buildResult.displayPath
                settings.lastDailyGeneratedAt = System.currentTimeMillis()
                SyncAndBuildResult(syncResult, buildResult)
            }
        }) { result ->
            status.text = if (result.build == null) {
                "同步完成：新增 ${result.sync.newArticleCount}，当前筛选无可生成文章"
            } else {
                "已同步并生成 ${result.build.displayPath}（${result.build.articleCount} 篇）"
            }
            showToday()
        }
    }

    private fun markTodayRead(articles: List<Article>) {
        val unread = articles.filterNot { it.isRead }
        if (unread.isEmpty()) {
            status.text = "当前列表没有未读文章"
            return
        }
        unread.forEach { database.setRead(it.id, true) }
        status.text = "已标记 ${unread.size} 篇为已读"
        showToday()
    }

    private fun openOutputDirectory() {
        val uri = settings.outputTreeUri
        if (uri == null) {
            status.text = "请先选择输出目录"
            outputDirectoryLauncher.launch(null)
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { status.text = "当前设备无法直接打开目录，请使用系统文件管理器" }
    }

    private fun checkForUpdatesIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheckAt < TimeUnit.DAYS.toMillis(1)) return
        checkForUpdates(manual = false)
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) status.text = "正在检查更新…"
        executor.execute {
            runCatching {
                updateChecker.check(currentVersionName())
            }.fold(
                onSuccess = { update ->
                    settings.lastUpdateCheckAt = System.currentTimeMillis()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (update == null) {
                            if (manual) status.text = "当前已是最新版本"
                        } else {
                            status.text = "发现新版本 ${update.version}"
                            showUpdateDialog(update)
                        }
                    }
                },
                onFailure = { error ->
                    if (!manual) settings.lastUpdateCheckAt = System.currentTimeMillis()
                    runOnUiThread {
                        if (!isDestroyed && manual) {
                            status.text = "检查更新失败：${error.message ?: "未知错误"}"
                        }
                    }
                }
            )
        }
    }

    private fun showUpdateDialog(update: UpdateInfo) {
        val notes = update.notes
            ?.lineSequence()
            ?.filter { it.isNotBlank() }
            ?.take(6)
            ?.joinToString("\n")
            .orEmpty()
        val message = buildString {
            appendLine("当前版本：${currentVersionName()}")
            appendLine("最新版本：${update.version}")
            update.publishedAt?.let { appendLine("发布时间：$it") }
            if (notes.isNotBlank()) {
                appendLine()
                append(notes)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage(message)
            .setPositiveButton("打开下载页") { _, _ -> openBrowser(update.downloadUrl.ifBlank { update.pageUrl }) }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onFailure { status.text = "当前设备无法打开浏览器：$url" }
    }

    private fun currentVersionName(): String =
        runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.0"
        }.getOrDefault("0.0.0")

    private fun <T> runTask(message: String, task: () -> T, success: (T) -> Unit) {
        status.text = message
        executor.execute {
            runCatching(task).fold(
                onSuccess = { runOnUiThread { if (!isDestroyed) success(it) } },
                onFailure = { error ->
                    runOnUiThread {
                        if (!isDestroyed) {
                            status.text = "操作失败：${error.message ?: "未知错误"}"
                        }
                    }
                }
            )
        }
    }

    private fun refreshPage() {
        navigationButtons.forEach { (target, button) ->
            button.background = buttonBackground(selected = target == page)
        }
        when (page) {
            Page.TODAY -> showToday()
            Page.SOURCES -> showSources()
            Page.STARRED -> showStarred()
            Page.SETTINGS -> showSettings()
        }
    }

    private fun show(view: View) {
        content.removeAllViews()
        pageScrollView = PagingScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isSmoothScrollingEnabled = false
            addView(view)
            viewTreeObserver.addOnGlobalLayoutListener {
                updatePageIndicator()
            }
        }
        content.addView(pageScrollView)
        updatePageIndicator()
    }

    private fun changePage(direction: Int) {
        val scroll = pageScrollView ?: return
        val positions = pagePositions(scroll)
        val tolerance = dp(4)
        val target = if (direction > 0) {
            positions.firstOrNull { it > scroll.scrollY + tolerance } ?: positions.last()
        } else {
            positions.lastOrNull { it < scroll.scrollY - tolerance } ?: positions.first()
        }
        Log.d(TAG, "changePage: direction=$direction current=${scroll.scrollY} target=$target positions=$positions")
        scroll.scrollTo(0, target)
        updatePageIndicator()
    }

    private fun updatePageIndicator() {
        if (!::pageIndicator.isInitialized) return
        val scroll = pageScrollView ?: return
        val pageHeight = scroll.height
        if (pageHeight <= 0) {
            pageIndicator.text = "1 / 1"
            return
        }
        val positions = pagePositions(scroll)
        val total = positions.size
        val current = positions.indexOfLast { it <= scroll.scrollY + dp(4) }
            .coerceAtLeast(0) + 1
        pageIndicator.text = "$current / $total"
        Log.d(TAG, "updatePageIndicator: current=$current total=$total scrollY=${scroll.scrollY} positions=$positions")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_PAGE_UP -> {
                    changePage(-1)
                    return true
                }
                KeyEvent.KEYCODE_PAGE_DOWN -> {
                    changePage(1)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownX = event.x
                gestureDownY = event.y
                pagingGestureConsumed = false
                pendingPageDirection = 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pagingGestureConsumed) {
                    val dx = event.x - gestureDownX
                    val dy = event.y - gestureDownY
                    if (kotlin.math.abs(dy) > pagingTouchSlop &&
                        kotlin.math.abs(dy) > kotlin.math.abs(dx)
                    ) {
                        pagingGestureConsumed = true
                        pendingPageDirection = if (dy < 0) 1 else -1
                        Log.d(TAG, "dispatchTouchEvent: page gesture dy=$dy dx=$dx threshold=$pagingTouchSlop")
                        MotionEvent.obtain(event).also { cancel ->
                            cancel.action = MotionEvent.ACTION_CANCEL
                            super.dispatchTouchEvent(cancel)
                            cancel.recycle()
                        }
                        return true
                    }
                }
                if (pagingGestureConsumed) return true
            }
            MotionEvent.ACTION_UP -> {
                if (pagingGestureConsumed) {
                    pagingGestureConsumed = false
                    val direction = pendingPageDirection
                    pendingPageDirection = 0
                    if (direction != 0) changePage(direction)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pagingGestureConsumed) {
                    pagingGestureConsumed = false
                    pendingPageDirection = 0
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun pageLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(24))
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        tag = PAGING_ANCHOR
        text = value
        textSize = 19f
        setTextColor(Color.BLACK)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(8))
    }

    private fun bodyText(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.DKGRAY)
        setLineSpacing(0f, 1.2f)
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun emptyText(value: String) = bodyText(value).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(36), dp(12), dp(36))
    }

    private fun itemBox() = LinearLayout(this).apply {
        tag = PAGING_ANCHOR
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(14), 0, dp(14))
        background = dividerBottom()
    }

    private fun buttonRow(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        setPadding(0, 0, 0, dp(8))
        views.forEach {
            addView(it, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(3)
                marginEnd = dp(3)
            })
        }
    }

    private fun fullWidthButtonParams(bottomMargin: Int = 0) =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            this.bottomMargin = dp(bottomMargin)
        }

    private fun pagePositions(scroll: ScrollView): List<Int> {
        val pageHeight = scroll.height.coerceAtLeast(1)
        val contentHeight = scroll.getChildAt(0)?.height ?: pageHeight
        val maxScroll = (contentHeight - pageHeight).coerceAtLeast(0)
        val root = scroll.getChildAt(0) as? ViewGroup
            ?: return calculatePagePositions(
                anchors = emptyList(),
                pageHeight = pageHeight,
                maxScroll = maxScroll,
                minimumLastPage = maxOf(dp(48), pageHeight / 5)
            )
        val anchors = buildList {
            collectPagingAnchors(root, root, this)
        }.filter { it in 1..maxScroll }.distinct().sorted()
        return calculatePagePositions(
            anchors = anchors,
            pageHeight = pageHeight,
            maxScroll = maxScroll,
            minimumLastPage = maxOf(dp(48), pageHeight / 5)
        )
    }

    private fun collectPagingAnchors(root: View, view: View, anchors: MutableList<Int>) {
        if (view.tag === PAGING_ANCHOR) {
            val location = IntArray(2)
            val rootLocation = IntArray(2)
            view.getLocationInWindow(location)
            root.getLocationInWindow(rootLocation)
            anchors += location[1] - rootLocation[1]
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                collectPagingAnchors(root, view.getChildAt(index), anchors)
            }
        }
    }

    private fun outlineButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(Color.BLACK)
        background = buttonBackground()
        stateListAnimator = null
        minHeight = dp(52)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        setOnClickListener { action() }
    }

    private fun buttonBackground(selected: Boolean = false) =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(7).toFloat()
            setColor(if (selected) 0xFFE6E6E6.toInt() else Color.WHITE)
            setStroke(dp(if (selected) 2 else 1), Color.BLACK)
        }

    private fun choiceSpinner(items: List<String>, selected: String, change: (String) -> Unit) =
        Spinner(this).apply {
            backgroundTintList = grayscaleControlTint()
            adapter = object : ArrayAdapter<String>(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                items
            ) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getView(position, convertView, parent).apply {
                        if (this is TextView) {
                            setTextColor(Color.BLACK)
                            setBackgroundColor(Color.WHITE)
                        }
                    }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    super.getDropDownView(position, convertView, parent).apply {
                        if (this is TextView) {
                            setTextColor(Color.BLACK)
                            setBackgroundColor(Color.WHITE)
                        }
                    }
            }
            setSelection(items.indexOf(selected).coerceAtLeast(0))
            onItemSelectedListener = SimpleItemSelectedListener { change(items[it]) }
        }

    private fun settingCheck(label: String, checked: Boolean, change: (Boolean) -> Unit) =
        CheckBox(this).apply {
            text = label
            setTextColor(Color.BLACK)
            buttonTintList = grayscaleControlTint()
            isChecked = checked
            minHeight = dp(48)
            setOnCheckedChangeListener { _, value -> change(value) }
        }

    private fun grayscaleControlTint() = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_activated),
            intArrayOf()
        ),
        intArrayOf(Color.BLACK, Color.BLACK, Color.DKGRAY)
    )

    private fun inputBackground() =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(4).toFloat()
            setColor(Color.WHITE)
            setStroke(dp(1), Color.BLACK)
        }

    private fun divider() = android.graphics.drawable.ColorDrawable(Color.BLACK).apply {
        setBounds(0, 0, 1, 1)
    }

    private fun dividerBottom() = android.graphics.drawable.GradientDrawable().apply {
        setColor(Color.WHITE)
        setStroke(1, Color.LTGRAY)
    }

    private fun formatTime(value: Long?): String = value?.let {
        BeijingTime.dateTime(it)
    } ?: "未同步"

    private fun outputPath(): String =
        settings.outputTreeUri?.toString() ?: "应用私有目录 /InkFeed（请选择 /Documents/InkFeed）"

    private fun dailyPackageStatus(): String {
        val path = settings.lastDailyPath ?: return "尚未生成"
        return "$path · ${formatTime(settings.lastDailyGeneratedAt)}"
    }

    private fun todayArticles(): List<Article> {
        val dayStart = BeijingTime.startOfDayMillis()
        val articles = database.todayArticles(
            dayStartMillis = BeijingTime.startOfDayMillis(),
            limit = settings.dailyLimit
        )
        Log.d(TAG, "todayArticles: dayStart=$dayStart count=${articles.size} titles=${articles.joinToString { it.title.take(24) }}")
        return articles
    }

    private fun articlesForTodayFilter(articles: List<Article>): List<Article> =
        if (settings.todayUnreadOnly) articles.filterNot { it.isRead } else articles

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class SyncAndBuildResult(
        val sync: SyncResult,
        val build: BuildResult?
    )

    private enum class Page { TODAY, SOURCES, STARRED, SETTINGS }

    companion object {
        private const val TAG = "InkFeed"
        private val PAGING_ANCHOR = Any()
    }

}

internal fun calculatePagePositions(
    anchors: List<Int>,
    pageHeight: Int,
    maxScroll: Int,
    minimumLastPage: Int
): List<Int> {
    if (maxScroll <= 0) return listOf(0)
    val sortedAnchors = anchors.filter { it in 1..maxScroll }.distinct().sorted()
    val positions = mutableListOf(0)
    while (positions.last() < maxScroll) {
        val current = positions.last()
        val ideal = current + pageHeight
        if (ideal >= maxScroll) {
            positions += maxScroll
            break
        }
        val next = sortedAnchors.lastOrNull { it in (current + 1)..ideal } ?: ideal
        if (next <= current) break
        positions += next
    }
    if (positions.size > 2 &&
        positions.last() - positions[positions.lastIndex - 1] < minimumLastPage
    ) {
        positions.removeAt(positions.lastIndex - 1)
    }
    return positions
}

private class SimpleItemSelectedListener(
    private val selected: (Int) -> Unit
) : android.widget.AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: android.widget.AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long
    ) = selected(position)

    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
}

private class PagingScrollView(context: android.content.Context) : ScrollView(context) {

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
