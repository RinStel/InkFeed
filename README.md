# InkFeed

InkFeed（墨流）是一款面向 Android 墨水屏设备的离线 RSS / Atom 阅读工具。

它可以同步订阅源、提取文章正文并生成 EPUB 阅读包，适合与 KOReader 配合使用。

## 主要功能

- 添加 RSS / Atom 订阅源
- 导入 OPML 订阅列表
- 按北京时间整理今日文章
- 标记文章已读/未读与收藏
- 缓存正文与灰度图片
- 生成每日及收藏 EPUB 阅读包
- 按订阅源拆分 EPUB
- 使用整页翻页交互，减少墨水屏连续刷新
- 支持 Android 系统文档目录
- 自动检查 GitHub Releases 更新，并跳转浏览器下载新版 APK

## 使用方式

1. 在“订阅源”页面添加 RSS / Atom 地址，或导入 OPML 文件。
2. 点击“同步全部”下载最新文章。
3. 在“设置”页面选择 EPUB 输出目录。
4. 回到“今日”页面生成 EPUB。
5. 使用 KOReader 或其他 EPUB 阅读器打开生成的文件。

## 系统要求

- Android 8.0（API 26）或更高版本
- 需要网络权限同步订阅源和文章
- Release 包不包含原生 `.so`，对 `arm64-v8a` 与 `armeabi-v7a` 设备都更稳妥

## 兼容性说明

- 时间与日期逻辑已避免依赖部分厂商 ROM 缺失的 `java.time` API
- 缓存清理在应用启动后按天执行一次，不依赖后台任务调度器
- 若设备厂商对后台、文件系统或 Web 内容抓取有额外限制，仍应以真机安装测试为准

## 从源码构建

使用 Android Studio 打开项目，或在已配置 Android SDK 的环境中运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

## 注意事项

- 文章提取效果取决于目标网站的页面结构。
- InkFeed 不提供 RSS 内容，文章版权归原作者或发布方所有。
