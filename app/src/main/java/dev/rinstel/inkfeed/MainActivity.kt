package dev.rinstel.inkfeed

import android.content.Intent
import android.graphics.Color
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import dev.rinstel.inkfeed.article.cache.CacheCleaner
import dev.rinstel.inkfeed.article.cache.CacheCleanupWorker
import dev.rinstel.inkfeed.core.database.InkFeedDatabase
import dev.rinstel.inkfeed.core.model.Article
import dev.rinstel.inkfeed.core.model.ImagePolicy
import dev.rinstel.inkfeed.core.model.Source
import dev.rinstel.inkfeed.core.util.AppSettings
import dev.rinstel.inkfeed.core.util.BeijingTime
import dev.rinstel.inkfeed.epub.builder.EpubBuilder
import dev.rinstel.inkfeed.feed.opml.OpmlImporter
import dev.rinstel.inkfeed.feed.sync.FeedSyncService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var database: InkFeedDatabase
    private lateinit var settings: AppSettings
    private lateinit var syncService: FeedSyncService
    private lateinit var epubBuilder: EpubBuilder
    private lateinit var cacheCleaner: CacheCleaner
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        database = InkFeedDatabase(this)
        settings = AppSettings(this)
        syncService = FeedSyncService(this, database, settings)
        epubBuilder = EpubBuilder(this, settings, database)
        cacheCleaner = CacheCleaner(database)
        CacheCleanupWorker.schedule(this)
        setContentView(buildRoot())
        showToday()
    }

    override fun onDestroy() {
        syncService.cancel()
        executor.shutdownNow()
        super.onDestroy()
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
        Log.d(TAG, "showToday: articles=${articles.size} synced=${articles.map { it.syncedAt ?: 0L }}")
        val sources = database.sources()
        val root = pageLayout()
        root.addView(sectionTitle("今日阅读包"))
        val lastSync = sources.mapNotNull { it.lastSyncAt }.maxOrNull()
        root.addView(bodyText(
            "今日文章：${articles.size} 篇\n" +
                "预计阅读：${articles.sumOf { it.readingMinutes }} 分钟\n" +
                "最近同步：${formatTime(lastSync)}\n" +
                "阅读包：${dailyPackageStatus()}\n" +
                "输出目录：${outputPath()}"
        ))
        root.addView(buttonRow(
            outlineButton("同步全部") { syncAll() },
            outlineButton(if (settings.lastDailyPath == null) "生成 EPUB" else "重新生成") {
                buildDaily()
            }
        ))
        root.addView(buttonRow(
            outlineButton("打开输出目录") { openOutputDirectory() },
            outlineButton("选择目录") { outputDirectoryLauncher.launch(settings.outputTreeUri) }
        ))
        root.addView(sectionTitle("文章"))
        if (articles.isEmpty()) {
            root.addView(emptyText("尚无文章。请先在“订阅源”添加 RSS / Atom 并同步。"))
        } else {
            articles.forEach { root.addView(articleView(it, allowUnstar = false)) }
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
        val title = EditText(this).apply { hint = "名称（可留空）" }
        val url = EditText(this).apply {
            hint = "https://example.com/feed.xml"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
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
            "${formatTime(article.publishedAt)} · 预计阅读 ${article.readingMinutes} 分钟"
        ))
        box.addView(outlineButton(
            if (allowUnstar || article.isStarred) "取消收藏" else "收藏"
        ) {
            database.setStarred(article.id, !article.isStarred)
            status.text = if (article.isStarred) "已取消收藏" else "已收藏"
            refreshPage()
        })
        return box
    }

    private fun syncAll() {
        runTask("正在同步全部订阅源…", { syncService.syncAll() }) { result ->
            status.text = if (result.errors.isEmpty()) {
                "同步完成：新增 ${result.newArticleCount}，重复 ${result.duplicateCount}，" +
                    "失败 ${result.failedArticleCount}"
            } else {
                "同步完成：新增 ${result.newArticleCount}，文章失败 ${result.failedArticleCount}，" +
                    "订阅源错误 ${result.errors.size}"
            }
            showToday()
        }
    }

    private fun buildDaily() {
        val articles = todayArticles()
        if (articles.isEmpty()) {
            status.text = "没有可生成的文章"
            return
        }
        runTask("正在生成每日 EPUB…", { epubBuilder.buildDaily(articles) }) {
            database.markPackaged(articles.map(Article::id))
            settings.lastDailyPath = it.displayPath
            settings.lastDailyGeneratedAt = System.currentTimeMillis()
            status.text = "已生成 ${it.displayPath}（${it.articleCount} 篇，${it.packageCount} 个文件）"
            showToday()
        }
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
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                items
            )
            setSelection(items.indexOf(selected).coerceAtLeast(0))
            onItemSelectedListener = SimpleItemSelectedListener { change(items[it]) }
        }

    private fun settingCheck(label: String, checked: Boolean, change: (Boolean) -> Unit) =
        CheckBox(this).apply {
            text = label
            isChecked = checked
            minHeight = dp(48)
            setOnCheckedChangeListener { _, value -> change(value) }
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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
