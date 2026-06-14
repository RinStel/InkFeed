# InkFeed PRD

## 1. 项目信息

```text
App 英文名：InkFeed
App 中文名：墨流
组织名：RinStel
Android applicationId：dev.rinstel.inkfeed
Kotlin namespace：dev.rinstel.inkfeed
GitHub 仓库名：inkfeed
本地输出目录：/Documents/InkFeed/
项目描述：面向墨水屏的离线信息流阅读器
英文描述：An offline feed reader for E Ink devices.
```

Android 基础配置：

```kotlin
android {
    namespace = "dev.rinstel.inkfeed"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.rinstel.inkfeed"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.1.1"
    }
}
```

最低系统版本为 Android 8.0 / API 26。首版面向仍适合安装第三方 App 的 Android 墨水屏设备。
首版应避免依赖厂商 ROM 可能裁剪的 `java.time` 新接口，并尽量减少后台组件与无关依赖。

## 2. 产品定位

InkFeed 是面向墨水屏设备的信息流阅读应用。它的首版形态是“离线信息流管理器 + EPUB 阅读包生成器”。

联网时，InkFeed 批量同步 RSS / Atom 信息源，抓取文章，提取正文，缓存文本和图片。离线时，用户通过生成的 EPUB 阅读包在 KOReader 中阅读新闻、博客、公告和长文。

首版重点不是构建完整阅读器，而是把在线信息流整理成适合墨水屏阅读的离线内容包。

核心定位：

```text
RSS / Atom / OPML 订阅管理
文章抓取与正文提取
本地离线缓存
灰度图片处理
EPUB 阅读包生成
KOReader 协作阅读
```

## 3. 目标用户

主要目标用户：

```text
使用 Android 墨水屏电子书的用户
希望离线阅读新闻、博客和公告的用户
使用 KOReader 阅读长文的用户
希望减少信息流干扰的用户
希望把 RSS 内容整理成每日阅读包的用户
```

首版优先考虑开放 Android 墨水屏设备，例如文石、墨案、Bigme 等可安装第三方 App 的设备。

## 4. 设计原则

### 4.1 墨水屏优先

界面必须按墨水屏显示特性设计：

```text
减少动画
减少连续滚动
减少频繁刷新
减少高频状态变化
减少彩色依赖
优先黑白灰界面
优先静态布局
优先分页式交互
优先明确按钮操作
```

应用体验应接近电子书工具，而非手机端 Material 信息流应用。

### 4.2 离线优先

内容同步完成后，文章正文、图片和阅读包应可在离线环境下访问。

```text
Feed 元数据本地保存
文章正文本地保存
必要图片本地保存
EPUB 文件本地保存
收藏文章长期保留
```

### 4.3 阅读端复用 KOReader

首版阅读体验交给 KOReader。InkFeed 负责内容组织与文件生成，KOReader 负责分页阅读、字体、边距、行距、刷新设置、进度和笔记。

## 5. 首版功能范围

### 5.1 必须实现

```text
RSS / Atom 添加
RSS / Atom 删除
RSS / Atom 启用与停用
OPML 导入
手动同步所有订阅源
手动同步单个订阅源
文章元数据解析
文章去重
正文提取
文章本地缓存
图片下载
图片缩放
图片灰度处理
每日 EPUB 生成
收藏 EPUB 生成
输出目录设置
Today 页面
Sources 页面
Starred 页面
Settings 页面
```

### 5.2 暂缓实现

```text
账号系统
云同步
推荐算法
社交分享
完整自研阅读器
自动摘要
多设备进度同步
复杂标签系统
高级全文搜索
评论区抓取
```

## 6. 页面设计

### 6.1 Today 页面

Today 页面展示当天同步结果和阅读包状态。

主要内容：

```text
今日阅读包状态
今日新增文章数量
预计阅读时间
最近同步时间
生成 EPUB 按钮
重新生成 EPUB 按钮
打开输出目录按钮
文章列表
```

文章列表样式应简洁：

```text
[来源]
文章标题
摘要前两行
发布时间 · 预计阅读 5 分钟
────────────────
```

墨水屏要求：

```text
列表项使用固定高度或近似固定高度
使用黑白高对比分隔线
避免卡片阴影
避免滑动动画
避免 shimmer 加载动画
同步中使用静态文字
```

状态文案示例：

```text
未同步
同步中
已同步 32 篇
生成 EPUB 中
已生成 daily/2026-06-14.epub
```

### 6.2 Sources 页面

Sources 页面管理订阅源。

功能：

```text
添加 RSS / Atom
导入 OPML
查看订阅源列表
启用订阅源
停用订阅源
删除订阅源
同步单个订阅源
查看单个订阅源文章
```

订阅源字段：

```text
名称
Feed URL
站点 URL
分组
启用状态
最近同步时间
最近同步结果
```

### 6.3 Starred 页面

Starred 页面管理收藏文章。

功能：

```text
查看收藏文章
取消收藏
生成收藏 EPUB
查看收藏阅读包路径
```

收藏文章应长期保留缓存，清理策略默认跳过收藏文章。

### 6.4 Settings 页面

Settings 页面提供基础配置。

配置项：

```text
输出目录
缓存天数
图片策略
每日阅读包最大文章数
是否生成 HTML 调试文件
是否按来源拆分 EPUB
同步时是否下载图片
KOReader 协作说明
```

图片策略：

```text
无图模式
仅首图
正文必要图片
完整图片
```

## 7. 技术方案

### 7.1 技术栈

```text
语言：Kotlin
UI：传统 View（LinearLayout / TextView / Button 等）
数据库：SQLite（SQLiteOpenHelper）
后台任务：应用启动时按需执行（不依赖 WorkManager）
网络：OkHttp
RSS / Atom 解析：Jsoup XML Parser（不依赖 Android XmlPullParser）
HTML 解析：Jsoup
正文提取：Readability 类算法
图片处理：Android Bitmap
文件访问：Storage Access Framework / 应用私有目录
阅读包格式：EPUB（ZIP 格式直接构建，无第三方 EPUB 库）
调试输出：HTML
```

### 7.2 包结构

```text
dev.rinstel.inkfeed
├── core
│   ├── database
│   ├── network
│   ├── model
│   └── util
├── feed
│   ├── parser
│   ├── sync
│   └── opml
├── article
│   ├── extractor
│   ├── cache
│   └── filter
├── epub
│   ├── builder
│   ├── template
│   └── asset
├── reader
│   ├── koreader
│   └── internal
└── ui
    ├── today
    ├── source
    ├── starred
    └── settings
```

首版可以采用单 Gradle 模块。内部包结构需要清晰，后续再拆分模块。

## 8. 数据模型

### 8.1 Source

```kotlin
data class Source(
    val id: Long,
    val title: String,
    val feedUrl: String,
    val siteUrl: String?,
    val groupName: String?,
    val enabled: Boolean,
    val lastSyncAt: Long?
)
```

### 8.2 Article

```kotlin
data class Article(
    val id: Long,
    val sourceId: Long,
    val title: String,
    val url: String,
    val guid: String?,
    val author: String?,
    val publishedAt: Long?,
    val summary: String?,
    val contentText: String?,
    val contentHtml: String?,
    val contentJsonPath: String?,
    val readingMinutes: Int,
    val isRead: Boolean,
    val isStarred: Boolean,
    val cachedAt: Long?,
    val addedToDailyPackage: Boolean
)
```

### 8.3 ArticleAsset

```kotlin
data class ArticleAsset(
    val id: Long,
    val articleId: Long,
    val originalUrl: String,
    val localPath: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?
)
```

### 8.4 Tag

Tag 与 ArticleTag 可以预留：

```kotlin
data class Tag(
    val id: Long,
    val name: String
)

data class ArticleTag(
    val articleId: Long,
    val tagId: Long
)
```

首版可以只保留表结构，界面层暂缓完整标签功能。

## 9. 文章同步与处理

### 9.1 同步流程

```text
用户点击同步
读取启用的订阅源
拉取 RSS / Atom XML
解析文章元数据
按规则去重
下载新文章网页
提取正文
归一化正文内容
下载必要图片
缩放图片
转换灰度图片
保存文章与资源
生成 HTML 调试文件
生成或更新 EPUB 阅读包
刷新页面状态
```

### 9.2 去重规则

去重优先级：

```text
1. RSS guid
2. canonical URL
3. 标题 + 来源 + 发布时间
```

### 9.3 正文归一化

文章抓取后统一转换为 block-based content。

```json
{
  "title": "文章标题",
  "source": "来源",
  "url": "原文链接",
  "publishedAt": "2026-06-14T09:00:00+08:00",
  "blocks": [
    { "type": "heading", "level": 1, "text": "小标题" },
    { "type": "paragraph", "text": "正文内容" },
    { "type": "image", "src": "local://image-1.webp", "caption": "图注" },
    { "type": "quote", "text": "引用内容" },
    { "type": "code", "text": "代码内容" }
  ]
}
```

支持的 block 类型：

```text
heading
paragraph
image
quote
code
```

这个中间格式用于：

```text
EPUB 生成
HTML 调试输出
本地搜索
后续内置阅读器
```

## 10. 图片处理

墨水屏设备对图片显示和刷新较敏感，首版图片处理策略如下：

```text
默认限制图片宽度
默认转换为灰度
默认压缩图片体积
动图只取第一帧
列表页默认无图
正文 EPUB 中允许显示图片
无图模式可完全跳过图片下载
```

图片输出格式可以先使用 JPEG 或 WebP。若 EPUB 兼容性存在问题，优先使用 JPEG。

## 11. EPUB 生成

### 11.1 阅读包结构

```text
一本 EPUB = 一个阅读包
一个章节 = 一篇文章
目录 = 文章列表
```

阅读包类型：

```text
每日 EPUB
收藏 EPUB
按来源 EPUB，后续加入
```

目录结构：

```text
/Documents/InkFeed/
  daily/
    2026-06-14.epub
    2026-06-13.epub
  starred/
    starred.epub
  html-debug/
    2026-06-14/
      article-001.html
      article-002.html
```

### 11.2 每篇文章内容

每篇文章章节包含：

```text
标题
来源
发布时间
原文链接
正文
图片
```

### 11.3 EPUB CSS

CSS 保持简单，避免和 KOReader 的排版设置冲突。

```css
body {
  font-family: serif;
  line-height: 1.6;
  margin: 1.2em;
}

h1 {
  font-size: 1.4em;
}

.meta {
  font-size: 0.85em;
  color: #555;
}

img {
  max-width: 100%;
}
```

灰度图片应在生成 EPUB 前完成，避免依赖 CSS filter。

## 12. KOReader 协作

首版采用文件协作方式：

```text
InkFeed 生成 EPUB 到 /Documents/InkFeed/
用户在 KOReader 文件浏览器中打开 EPUB
```

InkFeed 负责：

```text
订阅源管理
文章同步
正文提取
图片处理
阅读包生成
收藏管理
```

KOReader 负责：

```text
分页阅读
字体调整
边距行距
墨水屏刷新设置
高亮笔记
阅读进度
长文阅读体验
```

首版暂时只提供打开输出目录或显示输出路径。后续可尝试通过 Android Intent 打开 EPUB 文件。

## 13. 墨水屏 UI 要求

### 13.1 视觉风格

```text
纯白背景
黑色正文
灰色辅助文字
简单分隔线
弱化卡片
弱化彩色元素
图标使用线性图标
按钮使用文字或简单描边
```

### 13.2 动画控制

应避免：

```text
页面切换动画
复杂过渡动画
列表项动画
shimmer 加载动画
大面积 ripple 效果
高频进度条动画
自动轮播
自动滚动
```

允许：

```text
极短的状态变化
静态进度文字
手动刷新后的状态提示
```

### 13.3 交互方式

优先：

```text
分页列表
明确按钮
手动同步
手动生成
静态状态文本
大触控区域
```

避免：

```text
无限信息流滚动
自动刷新可见内容
复杂手势
依赖颜色区分状态
```

### 13.4 首版实现说明

首版采用传统 View 体系（LinearLayout / TextView / Button 等），未使用 Jetpack Compose。

```kotlin
// 主题使用 AppCompat Light NoActionBar + 自定义属性
// 界面全部以代码构建，无 XML 布局依赖（避免 Android 布局解析开销）
```

主题偏黑白灰，动画完全禁用（`android:windowAnimationStyle` 设为 `@null`）。页面状态变化以文本和布局更新为主。

若后续引入 Compose，可参考以下初始设置（当前未使用）：

```kotlin
val LocalEInkMode = staticCompositionLocalOf { true }

@Composable
fun InkFeedTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = Color.White,
            surface = Color.White,
            onBackground = Color.Black,
            onSurface = Color.Black,
            primary = Color.Black,
            onPrimary = Color.White
        ),
        content = content
    )
}
```

## 14. 文件访问

Android 11 起文件访问限制较多。InkFeed 应使用 Storage Access Framework 让用户选择输出目录，并持久化目录 URI 权限。

首版默认引导用户选择：

```text
/Documents/InkFeed/
```

若系统或设备文件管理能力受限，可以在应用私有目录中生成文件，并提供导出功能。

## 15. 缓存策略

默认缓存策略：

```text
普通文章缓存 30 天
已读普通文章可被清理
收藏文章长期保留
EPUB 阅读包长期保留，除非用户手动删除
图片随文章清理
HTML 调试文件可手动清理
```

设置项：

```text
缓存天数：7 / 14 / 30 / 90
图片策略：无图 / 仅首图 / 正文必要图片 / 完整图片
HTML 调试输出：开 / 关
```

## 16. 最小可运行版本

最小可运行版本需要完成：

```text
添加一个 RSS 源
同步前 10 篇文章
解析文章标题、链接、发布时间
下载网页
提取正文
保存到本地数据库
生成 HTML 调试文件
生成每日 EPUB
在 Today 页面显示生成结果
在 Sources 页面显示订阅源
在 Starred 页面显示空状态
在 Settings 页面显示输出目录设置
```

最小验证目标：

```text
把一个在线 RSS 源转换成可由 KOReader 打开的离线 EPUB 阅读包。
```

## 17. 验收标准

首版功能验收：

```text
可以添加 RSS / Atom 源
可以导入 OPML
可以手动同步订阅源
可以保存文章元数据
可以提取正文
可以保存文章正文
可以处理图片
可以生成 daily EPUB
可以生成 starred EPUB
可以设置输出目录
可以在墨水屏上以低动画界面使用
```

EPUB 验收：

```text
EPUB 可以被 KOReader 打开
目录可以显示文章列表
每篇文章有标题、来源、时间、原文链接和正文
图片可以正常显示
正文在黑白屏上清晰可读
```

界面验收：

```text
主要页面无明显动画依赖
黑白灰显示清楚
列表可读性良好
按钮触控区域足够大
同步与生成状态以静态文字显示
```

## 18. 后续版本方向

后续版本可增加：

```text
轻量内置阅读器
全文搜索
关键词过滤
每日阅读包规则
按来源生成 EPUB
WebDAV 同步
本地摘要
网页稍后读
阅读状态管理
```

内置阅读器优先支持：

```text
标题
段落
图片
引用
代码块
字号调整
分页阅读
```

KOReader 继续作为高级阅读器保留。
