# InkFeed

InkFeed（墨流）是一款面向 Android 墨水屏设备的离线 RSS / Atom 阅读工具。

它可以同步订阅源、提取文章正文并生成 EPUB 阅读包，适合与 KOReader 配合使用。

## 主要功能

- 添加 RSS / Atom 订阅源
- 导入 OPML 订阅列表
- 按北京时间整理今日文章
- 缓存正文与灰度图片
- 生成每日及收藏 EPUB 阅读包
- 按订阅源拆分 EPUB
- 使用整页翻页交互，减少墨水屏连续刷新
- 支持 Android 系统文档目录

## 使用方式

1. 在“订阅源”页面添加 RSS / Atom 地址，或导入 OPML 文件。
2. 点击“同步全部”下载最新文章。
3. 在“设置”页面选择 EPUB 输出目录。
4. 回到“今日”页面生成 EPUB。
5. 使用 KOReader 或其他 EPUB 阅读器打开生成的文件。

## 系统要求

- Android 11（API 30）或更高版本
- 需要网络权限同步订阅源和文章

## 从源码构建

使用 Android Studio 打开项目，或在已配置 Android SDK 的环境中运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

## 注意事项

- 文章提取效果取决于目标网站的页面结构。
- InkFeed 不提供 RSS 内容，文章版权归原作者或发布方所有。

