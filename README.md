# TV Player

开源的 Android TV 视频播放器。

[![License](https://img.shields.io/badge/license-MIT-yellow.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20TV-3DDC84.svg)](https://developer.android.com/tv)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF.svg)](https://kotlinlang.org)
[![ExoPlayer](https://img.shields.io/badge/ExoPlayer-2.19.1-2C7BE5.svg)](https://github.com/google/ExoPlayer)
[![minSdk](https://img.shields.io/badge/minSdk-24-blue.svg)](https://developer.android.com)
[![compileSdk](https://img.shields.io/badge/compileSdk-36-blue.svg)](https://developer.android.com)
[![ABI](https://img.shields.io/badge/ABI-arm64--v8a-orange.svg)](tv_project)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](CONTRIBUTING.md)
[![Download](https://img.shields.io/badge/download-latest-3ee0b4.svg)](https://303562915.github.io/TVPlayer/)

TV Player 面向电视大屏与遥控器操作，基于 Kotlin + ExoPlayer，专注**本地播放与网络直链播放**，
不内置任何影视内容、不做页面解析、不需要账号登录。所有观看数据只保存在本机。

> 下载页：<https://303562915.github.io/TVPlayer/>（与 Releases 同源的 APK 镜像与 SHA-256）

---

## 特性

- **多格式播放**：基于 ExoPlayer 2.19.1，支持 HLS、MP4、MKV、AVI、FLV、WEBM、MOV、TS。
- **遥控器手势控制**
  - 单击：显示 / 隐藏控制栏
  - 双击：暂停 / 继续
  - 长按：临时倍速播放
  - 左右滑动：快退 / 快进（进度调节）
  - 左侧上下滑动：屏幕亮度
  - 右侧上下滑动：音量
- **播放控制**：倍速播放、画面比例切换、片头片尾跳过、上 / 下一集、自动连播。
- **播放列表**：选集列表、播放列表管理、多线路测速与自动切换。
- **本地视频**：内置本地视频浏览，可直接选择存储中的媒体文件播放。
- **播放历史**：自动记录播放进度，支持断点续播。
- **收藏**：收藏影片 / 剧集，快速回到常看内容。
- **主题**：浅色 / 深色主题切换。
- **缓存清理**：一键清理播放缓存与临时文件。
- **TV 优先 UI**：适配 D-Pad 焦点导航、大屏布局、横屏沉浸式播放。

> 本项目已移除影视聚合、视频源管理、网页解析、会员登录、下载、投屏、短剧、搜索等模块，
> 仅保留纯粹的播放器能力。

## 无需登录

TV Player **没有账号体系**：

- 不需要注册、登录、会员或激活码；
- 不请求任何用户身份信息，不上传观看记录；
- 播放历史、收藏、播放进度全部保存在**本机 Room 数据库与 SharedPreferences** 中；
- 卸载应用或清除应用数据后，这些本地数据随之删除（请自行承担丢失风险）。

## 项目结构

Android 子项目位于 `tv_project/`：

```text
tv_project/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── res/                     # 布局、图标、主题、字符串
│       └── java/com/tv/ball/
│           ├── data/                # Room 数据库、SharedPreferences、数据仓库
│           ├── domain/              # 用例与领域模型
│           ├── presentation/        # Activity / Fragment / ViewModel / 播放器 UI
│           ├── util/                # 通用工具
│           └── utils/               # 播放器、格式、手势等辅助实现
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/
└── gradlew / gradlew.bat
```

- 包名：`com.tv.ball`
- 版本工具链：AGP 8.11.2、compileSdk 36、minSdk 24
- 打包 ABI：仅 `arm64-v8a`

## 环境要求

| 项目 | 要求 |
| --- | --- |
| JDK | 17 |
| Android SDK Platform | 36 |
| AGP | 8.11.2 |
| Gradle | 仓库自带 Wrapper |
| minSdk | 24（Android 7.0） |
| 目标设备 | Android TV / TV 盒子（arm64-v8a） |

## 构建

```bash
cd tv_project
./gradlew :app:assembleDebug
```

Windows（PowerShell / CMD）：

```bat
cd tv_project
gradlew.bat :app:assembleDebug
```

产物路径：`tv_project/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`

## 推荐用 GitHub Actions 构建

不想在本地安装 JDK 17 + Android SDK 36？直接用仓库自带的 CI 构建：

1. 打开本仓库的 **Actions** 页面。
2. 左侧选择 **Build** 工作流，点击右侧 **Run workflow**（选择分支后运行）。
3. 等待任务完成后，进入该次运行详情的 **Artifacts** 区域。
4. 下载 **`tv-player-debug`** 压缩包，解压即得 debug APK。

同一个构建在 `push` 到 `main` / `master` 与提交 `pull_request` 时会自动触发，
无需本地配置签名。

## 下载（Releases）

仓库的 **Releases** 页面始终有可直接安装的 APK，不用等发版：

| Release | 触发方式 | 内容 |
| --- | --- | --- |
| `latest`（最新构建） | 每次 `push` 到 `main` / `master`，或手动 `Run workflow` | 覆盖更新的 debug 签名 APK `TVPlayer-latest-debug.apk` |
| `v*`（正式版本） | 推送 `v*` 标签（如 `v1.0.0`） | 有签名 Secrets 时为 release 包，未配置时自动回退 debug 签名包 |

打正式版本：

```bash
git tag v1.0.0
git push origin v1.0.0
```

release 包的签名通过 Repository Secrets 注入（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、
`KEY_ALIAS`、`KEY_PASSWORD`）；四项缺失时 CI 自动改用 debug 签名，保证下载的包一定能安装。

## 下载页（GitHub Pages）

**<https://303562915.github.io/TVPlayer/>**

- 下载页托管在公开仓库 [303562915/TVPlayer](https://github.com/303562915/TVPlayer)：
  免费账户的私有仓库不支持 Pages，所以站点单独发布（源码仍在本私有仓库）。
- 页面源文件在本仓库 `docs/`，站点仓库只放页面与构建产物镜像。
- 私有仓库的 Release 资产对外返回 404，站点仓库运行 `Sync & Deploy` 时用
  `SOURCE_TOKEN`（对 `303562915/tv` 有 `repo` 读权限的 PAT）拉取 Releases，
  镜像最近 3 个 APK 到 `apk/`，并把版本、体积、更新日期与 SHA-256 写入 `latest.json`。
- 未配置 `SOURCE_TOKEN` 时站点照样部署，只是数据不刷新。定时任务每天 04:23 UTC 同步一次，
  也可以在站点仓库 Actions 里手动 Run workflow。

## 安装

通过 adb 安装到电视 / 盒子：

```bash
adb install -r app-arm64-v8a-debug.apk
```

网络设备先连接：

```bash
adb connect <设备IP>:5555
adb install -r app-arm64-v8a-debug.apk
```

本项目只发布 `arm64-v8a`，32 位设备（armeabi-v7a / x86）无法安装。

## 权限说明

应用申请权限极少，仅用于播放功能本身：

| 权限 | 用途 |
| --- | --- |
| `READ_MEDIA_VIDEO`（Android 13+） | **只读**访问本地视频文件，用于本地视频浏览与播放 |
| `READ_EXTERNAL_STORAGE`（Android 12 及以下） | 同上，旧版本存储读取 |
| `INTERNET` | 播放网络视频（HLS / MP4 等直链流） |
| `ACCESS_NETWORK_STATE` | 判断网络状态，用于线路可用性检测与测速切换 |

- **只读本地视频**：不修改、不删除、不上传任何本地文件。
- **网络播放**：仅访问用户提供的播放地址，不做后台聚合、不做隐藏上报。
- 不申请通讯录、短信、定位、相机、麦克风等无关权限；**无账号、无登录、无广告 SDK**。

## 第三方依赖与协议

| 依赖 | 版本 | 许可证 |
| --- | --- | --- |
| [ExoPlayer (Media3 播放内核)](https://github.com/google/ExoPlayer) | 2.19.1 | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) | 4.12 | Apache-2.0 |
| [Glide](https://github.com/bumptech/glide) | 4.16 | Apache-2.0 |
| [Room](https://developer.android.com/jetpack/androidx/releases/room) | 2.6.1 | Apache-2.0 |
| [Gson](https://github.com/google/gson) | 随依赖锁定 | Apache-2.0 |
| [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 随依赖锁定 | Apache-2.0 |
| [Material Components](https://github.com/material-components/material-components-android) | 1.13.0 | Apache-2.0 |
| [AndroidX AppCompat](https://developer.android.com/jetpack/androidx/releases/appcompat) | 1.7.1 | Apache-2.0 |
| [AndroidX ConstraintLayout](https://developer.android.com/jetpack/androidx/releases/constraintlayout) | 2.2.0 | Apache-2.0 |

以上依赖均为 Apache-2.0 协议，与本项目的 MIT 协议兼容。分发本应用时请保留各依赖的许可证与版权声明。

## 免责声明

本项目**仅供学习与个人使用**。

- 应用本身不提供、不聚合、不分发任何影视内容，也不内置任何视频源；
- 所有播放内容均由**用户自行提供**（本地文件或用户自备的网络地址），内容来源与合法性由用户自行负责；
- 请遵守你所在地区的法律法规以及内容服务方的使用条款；
- 使用本软件产生的任何后果由使用者自行承担，作者与贡献者不承担任何责任。

## 开源协议

本项目基于 [MIT License](LICENSE) 发布。

```text
Copyright (c) 2026 TV Player contributors
```

欢迎提交 Issue 与 Pull Request，请先阅读 [贡献指南](CONTRIBUTING.md) 与 [安全策略](SECURITY.md)。
