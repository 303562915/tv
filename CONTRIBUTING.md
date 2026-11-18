# 贡献指南

感谢你愿意为 **TV Player**（开源的 Android TV 视频播放器）提交代码、文档或问题反馈。
本文说明开发环境、提交流程与代码规范，请在动手前读完。

## 开发环境

| 项目 | 要求 |
| --- | --- |
| JDK | 17（AGP 8.11.2 要求 JDK 17） |
| Android Studio | Ladybug 或更新版本 |
| Android SDK Platform | 36（compileSdk 36） |
| Android SDK Build-Tools | 34.0.0 及以上 |
| Gradle | 使用仓库自带的 Gradle Wrapper，不要手动升级 |
| 目标 ABI | 仅 `arm64-v8a`（构建与测试均以此为准） |
| 设备 | Android TV / TV 盒子（minSdk 24），或 AOSP TV 模拟器 |

首次构建前请确认本地 `tv_project/local.properties` 中的 `sdk.dir` 指向你自己的 SDK 路径，该文件不会也不应提交到仓库。

## Fork / 分支 / PR 流程

1. Fork 本仓库到自己的账号。
2. 从最新的 `main`（或 `master`）拉出功能分支，分支名建议 `feat/xxx`、`fix/xxx`、`docs/xxx`：
   ```bash
   git checkout -b feat/gesture-brightness
   ```
3. 在 `tv_project/` 内完成改动，保持提交粒度单一、可回滚。
4. 提交前在本地验证（见下文「本地验证命令」）。
5. 推送到自己的 Fork 并向本仓库发起 Pull Request，目标分支为 `main`（或 `master`）。
6. PR 描述请使用 `.github/PULL_REQUEST_TEMPLATE.md` 的结构填写，关联对应 Issue（如 `Closes #12`）。
7. 维护者会基于 CI（GitHub Actions `Build` 工作流）结果与人工 review 决定是否合并。

## 提交信息规范（Conventional Commits）

格式：`<type>(<scope>): <subject>`

- `type` 必填，`scope` 可选，`subject` 使用中文或英文均可，不超过 72 字符，不加句号。
- 破坏性变更在 `type` 后加 `!`，或在 footer 写 `BREAKING CHANGE: ...`。

| type | 含义 | 示例 |
| --- | --- | --- |
| `feat` | 新增功能 | `feat(player): 支持长按 3 倍速播放` |
| `fix` | 修复缺陷 | `fix(history): 修复断点续播进度丢失` |
| `refactor` | 重构，不改变外部行为 | `refactor(data): 拆分 Room DAO` |
| `perf` | 性能优化 | `perf(player): 减少播放页重组次数` |
| `style` | 代码格式、空白、命名（不影响逻辑） | `style: 统一 4 空格缩进` |
| `docs` | 文档变更 | `docs: 补充构建命令` |
| `test` | 测试相关 | `test(util): 补充时间格式化用例` |
| `build` | 构建脚本、依赖、Gradle 配置 | `build: 升级 ExoPlayer 到 2.19.1` |
| `ci` | CI 配置 | `ci: 缓存 Gradle 依赖` |
| `chore` | 杂项、工具配置 | `chore: 更新 .gitignore` |

## 代码规范

- 使用 **Kotlin**，缩进统一 **4 空格**，不使用 Tab；续行缩进 8 空格。
- 遵循官方 Kotlin 编码约定：类名 `UpperCamelCase`，函数与变量 `lowerCamelCase`，常量 `UPPER_SNAKE_CASE`。
- 单个文件建议不超过 500 行；超过时按职责拆分到 `data/`、`domain/`、`presentation/`、`util/`、`utils/` 对应包。
- 新增 UI 使用 Material Components + AndroidX Navigation，不要引入第二套 UI 框架。
- 异步逻辑统一使用 Kotlin Coroutines，禁止在主线程做 IO 或数据库操作。
- 播放相关改动必须说明对 ExoPlayer 生命周期、手势冲突、倍速状态的影响。
- 新增第三方依赖需在 PR 中说明许可证（须为 Apache-2.0 / MIT 等宽松协议）并在 README 的依赖协议表中登记。

## 禁止提交的内容

- **任何凭据**：keystore、`*.jks`、`*.keystore`、密码、Token、API Key、Cookie、签名口令。
- **真实域名与真实接口地址**：示例请使用 `example.com`、`127.0.0.1` 或 `[PLACEHOLDER]` 占位。
- 个人隐私信息：真实姓名、手机号、邮箱、设备序列号、内网 IP。
- 大体积媒体文件与构建产物（`build/`、`*.apk`、`*.aab`、`*.mp4`、`*.mkv`）。
- `local.properties`、`.env`、IDE 个人配置。

仓库的 CI 中 `secret-scan` 任务会用 grep 扫描常见硬编码密钥模式，命中即失败。

## 本地验证命令

```bash
# 进入 Android 子项目
cd tv_project

# 编译 Debug APK（Linux / macOS）
./gradlew :app:assembleDebug

# 编译 Debug APK（Windows PowerShell / CMD）
gradlew.bat :app:assembleDebug

# 单元测试
./gradlew :app:testDebugUnitTest

# 静态检查
./gradlew :app:lintDebug
```

安装到已连接设备：

```bash
adb install -r tv_project/app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

TV 模拟器或电视盒子通过 `adb connect <IP>:5555` 连接后同样适用。

## 文档与反馈

- 缺陷请用 `.github/ISSUE_TEMPLATE/bug_report.yml` 提交，附设备型号、Android 版本与日志。
- 功能建议请用 `.github/ISSUE_TEMPLATE/feature_request.yml` 提交，说明使用场景。
- 安全问题请勿开公开 Issue，按 [SECURITY.md](SECURITY.md) 的私密渠道上报。

提交 PR 即表示你同意以本项目的 MIT 协议授权你的贡献。
