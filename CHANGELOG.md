# Changelog

本项目的所有重要变更都会记录在此文件。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.2.0] - 2026-11-21

### Added

- 双渠道发布（对照 unicanta）：每次 push 由 `Development APK` 工作流构建开发版并追加到
  滚动 `dev` 预发布 Release（`TVPlayer-dev-<run>.apk` 独立命名），`v*` 标签发稳定版；
  下载页新增开发版 / 稳定版切换（`development.json` + `latest.json`）。
- 关于页重建：应用英雄区、检查更新卡（失败点名原因）、稳定版 / 开发版渠道切换、
  自动下载安装开关、下载页与仓库外链（对照 unicanta AboutScreen 结构）。
- 更新检查支持渠道与失败原因：配额烧完 / 超时 / 网络不可达 / 上游故障 / 无发布 /
  响应异常，镜像 → GitHub API → Release 页面三级兜底，瞬态失败自动重试一次。
- 桌面图标按 unicanta 风格重做：品牌绿渐变底 + 黑色 TV 剪影（含镂空播放三角与底座）。

### Fixed

- 悬浮胶囊底栏在浅色模式下与背景融为一体、按钮不可见：两种模式统一使用深色胶囊。
- 检查更新镜像请求失败时 GitHub API 兜底永远不执行的问题（单层 runCatching 吞掉全部路径）。

### Changed

- versionCode 2 → 3，versionName 1.1.0 → 1.2.0。

## [1.0.0] - 2026-11-18

首个开源版本。定位为**开源的 Android TV 视频播放器**：面向电视大屏与遥控器操作，
专注本地与网络直链播放，无需登录，所有数据保存在本机。

### Added

- 基于 Kotlin + ExoPlayer 2.19.1 的播放内核，支持 HLS、MP4、MKV、AVI、FLV、WEBM、MOV、TS。
- 遥控器手势控制：单击显隐控制栏、双击暂停/继续、长按倍速、左右滑动调节进度、
  左侧上下滑动调节亮度、右侧上下滑动调节音量。
- 倍速播放。
- 画面比例切换。
- 片头片尾跳过。
- 播放列表与选集，上 / 下一集切换。
- 自动连播。
- 多线路测速与线路切换。
- 本地视频浏览与播放。
- 播放历史与断点续播（Room 数据库持久化）。
- 收藏功能。
- 浅色 / 深色主题切换。
- 缓存清理。
- 适配 D-Pad 焦点导航的 TV 优先 UI。
- GitHub Actions 构建流水线：Debug APK 构建与 artifact 上传、tag 触发的 Release 构建、密钥泄漏扫描。

### Changed

- 存储方案统一为本机 Room 数据库 + SharedPreferences，不依赖任何远程账号服务。
- 仅打包 `arm64-v8a`，减小安装包体积。

### Removed

- 移除影视聚合模块。
- 移除登录模块（无需账号即可使用全部功能）。
- 移除下载模块。
- 移除投屏模块。
- 移除短剧模块。
- 移除搜索模块。
