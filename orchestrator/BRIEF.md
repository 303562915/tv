# 子智能体任务简报（统一模板）

你是被主智能体派来的一枚执行单元。主智能体不读你的过程，只收你的交付物。
**唯一交付方式：把「完整文件内容」写进 staging 目录。** 不要自己 apply、不要自己 commit、不要自己跑构建。

## 0. 环境事实

- 仓库根：`G:\trae\tv`（git 仓库）
- Android 工程：`G:\trae\tv\tv_project`，包名 `com.tv.ball`
- 源码根：`G:\trae\tv\tv_project\app\src\main\java\com\tv\ball`
- 资源根：`G:\trae\tv\tv_project\app\src\main\res`
- 工具链：JDK 17（`C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot`）、Android SDK `G:\SDK`（platform 36 / build-tools 36.1.0）、Gradle wrapper 8.13
- 基线状态：`:app:assembleDebug` **当前通过**。任何会破坏它的写法都不许交。
- 技术栈：Kotlin 1.9 + 传统 XML 布局 + ViewBinding + Room(ksp) + ExoPlayer 2.19.1 + OkHttp 4.12 + Gson + androidx.appcompat。**没有 Compose，没有 Hilt，没有 Kotlin 反射注入框架。**
- 依赖注入：`com.tv.ball.di.Dependencies`（object，含 `context`、`database`、`configRepository` 等）。
- 主题配色的唯一色源：`res/values/colors.xml` + `res/values-night/colors.xml`，语义 token 已定义（`surface_page` / `surface_card` / `surface_block` / `surface_inset` / `theme_text_primary` / `theme_text_secondary` / `theme_primary` / `theme_divider` / `theme_error` …）。**新增颜色必须走 token，禁止硬编码十六进制。**
- 尺寸走 `res/values/dimens.xml`。

## 1. 你的工作流（严格按序）

1. `python G:\trae\tv\orchestrator\scripts\orchestrator.py show <TASK_ID>` —— 读任务定义（含验收标准、touch_files）。
2. `python G:\trae\tv\orchestrator\scripts\orchestrator.py prepare <TASK_ID>` —— 生成 `orchestrator/staging/<TASK_ID>/manifest.json`，里面列出要动的文件、绝对路径、是否已存在。
3. 用 read 工具读 manifest 里列出的**现有文件**（读就够，不要扩大范围）。项目里有大量历史风格，**跟随现有风格，不要重构别人的代码**。
4. 把要交付的**完整文件**写到：
   `G:\trae\tv\orchestrator\staging\<TASK_ID>\files\<相对路径>`
   相对路径规则：
   - Kotlin → `java/com/tv/ball/xxx/YourFile.kt`
   - 布局 → `res/layout/your_layout.xml`
   - 字符串 → `res/values/strings.xml`（**整文件全量**，见 §3）
   - 清单 → `app/src/main/AndroidManifest.xml`（**整文件全量**）
   - 构建脚本 → `app/build.gradle.kts`（整文件全量，慎用）
5. 写 `G:\trae\tv\orchestrator\staging\<TASK_ID>\notes\report.md`，格式：
   ```
   # <TASK_ID> <标题>
   ## 改动
   - 文件 → 做了什么
   ## 验收自检
   - [x] 每条验收标准逐条对照说明
   ## 新文件登记
   - 新增文件的短路径（java/… 或 res/…），主智能体需要 git add
   ## 风险
   - 可能影响的既有调用点 / 未覆盖的场景（没有就写"无"）
   ```
6. 用 `send_message` 把 `report.md` 的摘要（≤15 行）发回主智能体，然后结束。**不要再做别的事。**

## 2. 硬约束（违反即作废）

- **不许编译**（不要跑 gradlew，主智能体统一构建）。但你写的东西必须能编译：所有 import 完整，类型对得上，引用到的资源/属性/布局 id 必须真的存在或你在本次一并交付。
- **不许** `git add` / `git commit` / `git push` / `git reset`。
- **不许删文件**。需要替换就整文件重写。
- **只动 touch_files 里的文件**（可在 notes 里申请新增文件，主智能体会 git add）。
- 不许改 `android.enableJetifier` / `compileSdk` / `minSdk` / ABI 等构建基线。
- 不许新增第三方依赖（除非任务明确要求且已给出 artifact 坐标；新增前必须确认 Gradle 缓存里没有也要能联网拉取——不确定就**不要**新增依赖，用现有 API 实现）。
- 不许把网络请求、磁盘 IO 放在主线程；不许在没有生命周期保护的情况下持有 Activity 引用。
- 不许写 `TODO` / 空实现 / 假数据糊弄：功能必须是真能跑的。
- 中文注释、中文 UI 文案，风格与既有代码一致（`//` 说明"为什么"，不要复述代码）。
- minSdk 24，用到 `VERSION_CODES` 判断必须带版本守卫。TV 设备**没有触摸屏**，涉及焦点的地方必须考虑 D-Pad 可达。

## 3. 全量文件的合并纪律

`strings.xml`、`AndroidManifest.xml`、`colors.xml`、`dimens.xml`、`build.gradle.kts` 这类共享文件必须**整文件交付**，并且：
- 先 read 当前完整内容；
- 只在合适位置**追加/插入**你需要的条目，**保留其它所有既有条目原样**（一个字都不许删改）；
- 新字符串名加你的任务前缀避免撞名（如 `search_suggest_*`、`dlna_*`）。
- 你只能看到 prepare 时的快照，主智能体会做合并冲突检查；若你的文件比当前版本少了条目，主智能体会退单。

## 4. 验收门槛（主智能体会做）

1. apply 到仓库
2. `gradlew :app:assembleDebug` 必须通过（0 error）
3. 你 report 里「验收自检」必须逐条对上任务定义里的 acceptance
4. 通过 → 主智能体 commit（`feat(scope): 标题`）

做不到就**在 notes 里写清卡在哪**，交一个能编译的最小可用实现，别交编译不过的东西。
